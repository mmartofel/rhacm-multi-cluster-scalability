package com.redhat.banking.processor;

import io.quarkus.logging.Log;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Startup
@ApplicationScoped
public class KafkaPartitionStats {

    private static final String TOPIC = "transactions-raw";
    private static final int PARTITIONS = 24;

    private static final String SOURCE_CLUSTER = System.getenv().getOrDefault("SOURCE_CLUSTER", "unknown");

    /** Multi-topic config table — transactions-raw is ownership-split across clusters; the
     *  other two topics are per-cluster-local (not MM2-mirrored, not partition-split), so every
     *  partition is reported as fully "owned" by whichever cluster's transaction-processor is asking. */
    private static final List<TopicConfig> TOPICS = List.of(
            new TopicConfig("transactions-raw", 24, "transaction-processors-" + SOURCE_CLUSTER, true),
            new TopicConfig("transactions-committed", 3, "ledger-updaters-" + SOURCE_CLUSTER, false),
            new TopicConfig("transactions-dlq", 3, null, false)
    );

    @Inject
    TransactionProcessor processor;

    private AdminClient adminClient;
    private ScheduledExecutorService scheduler;

    private final String consumerGroup = "transaction-processors-" + SOURCE_CLUSTER;

    private volatile List<PartitionLag> cached = List.of();
    private volatile List<TopicLag> cachedTopics = List.of();
    private volatile Map<String, Map<Integer, Long>> prevEndOffsets = new HashMap<>();
    private volatile long prevRefreshMs = 0;
    private volatile Map<TopicPartition, Long> logDirBytesCache = new HashMap<>();

    @PostConstruct
    void init() {
        String bootstrap = System.getenv().getOrDefault(
                "KAFKA_BOOTSTRAP_SERVERS", "banking-kafka-kafka-bootstrap.banking-infra.svc.cluster.local:9092");
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "2000");
        props.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "4000");
        adminClient = AdminClient.create(props);
        Log.infof("KafkaPartitionStats initialized — group=%s bootstrap=%s", consumerGroup, bootstrap);

        // Proactively refresh cache every 3 s — decoupled from HTTP request path
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "kafka-lag-refresher");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::refreshCache, 1, 3, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(this::refreshLogDirs, 5, 30, TimeUnit.SECONDS);
    }

    @PreDestroy
    void close() {
        if (scheduler != null) scheduler.shutdownNow();
        if (adminClient != null) adminClient.close();
    }

    /** Always returns cached values — instant response, never blocks on Kafka. */
    public List<PartitionLag> getLag() {
        return cached;
    }

    /** Multi-topic view (transactions-raw / transactions-committed / transactions-dlq). */
    public List<TopicLag> getTopicStats() {
        return cachedTopics;
    }

    private void refreshCache() {
        try {
            refreshLegacySingleTopic();
        } catch (Exception e) {
            Log.warnf("Partition lag refresh failed: %s", e.getMessage());
        }
        try {
            refreshAllTopics();
        } catch (Exception e) {
            Log.warnf("Multi-topic Kafka stats refresh failed: %s", e.getMessage());
        }
    }

    /** Preserves the original single-topic (transactions-raw) cache untouched, so the existing
     *  "Traffic & Chaos" partition map keeps working exactly as before. */
    private void refreshLegacySingleTopic() throws Exception {
        Map<TopicPartition, OffsetSpec> latestSpecs = new HashMap<>();
        for (int p = 0; p < PARTITIONS; p++) {
            latestSpecs.put(new TopicPartition(TOPIC, p), OffsetSpec.latest());
        }

        var endFuture       = adminClient.listOffsets(latestSpecs).all();
        var committedFuture = adminClient.listConsumerGroupOffsets(consumerGroup)
                                         .partitionsToOffsetAndMetadata();

        Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> endOffsets =
                endFuture.get(2, TimeUnit.SECONDS);
        var committedResult = committedFuture.get(2, TimeUnit.SECONDS);

        var owned = processor.getOwnedPartitions();
        List<PartitionLag> result = new ArrayList<>(PARTITIONS);
        for (int p = 0; p < PARTITIONS; p++) {
            TopicPartition tp = new TopicPartition(TOPIC, p);
            long endOffset       = endOffsets.containsKey(tp) ? endOffsets.get(tp).offset() : 0L;
            var  committed       = committedResult.get(tp);
            long committedOffset = committed != null ? committed.offset() : 0L;
            long lag             = Math.max(0, endOffset - committedOffset);
            result.add(new PartitionLag(p, lag, owned.contains(p)));
        }

        cached = result;
        Log.debugf("Partition lag refreshed: %s", result);
    }

    private void refreshAllTopics() throws Exception {
        long nowMs = System.currentTimeMillis();
        double intervalSecs = prevRefreshMs > 0 ? Math.max(0.001, (nowMs - prevRefreshMs) / 1000.0) : 3.0;

        Map<TopicPartition, OffsetSpec> latestSpecs = new HashMap<>();
        for (TopicConfig tc : TOPICS) {
            for (int p = 0; p < tc.partitions; p++) {
                latestSpecs.put(new TopicPartition(tc.topic, p), OffsetSpec.latest());
            }
        }

        List<String> groups = TOPICS.stream()
                .map(tc -> tc.consumerGroup)
                .filter(g -> g != null)
                .distinct()
                .toList();

        // Launch every AdminClient call concurrently before awaiting any of them.
        var endOffsetsFuture = adminClient.listOffsets(latestSpecs).all();
        Map<String, KafkaFuture<Map<TopicPartition, org.apache.kafka.clients.consumer.OffsetAndMetadata>>> committedFutures = new HashMap<>();
        for (String group : groups) {
            committedFutures.put(group, adminClient.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata());
        }
        var groupDescribeFuture = groups.isEmpty()
                ? null
                : adminClient.describeConsumerGroups(groups).all();
        var topicDescribeFuture = adminClient.describeTopics(
                TOPICS.stream().map(tc -> tc.topic).toList()).allTopicNames();

        Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> endOffsets =
                endOffsetsFuture.get(2, TimeUnit.SECONDS);

        Map<String, Map<TopicPartition, org.apache.kafka.clients.consumer.OffsetAndMetadata>> committedByGroup = new HashMap<>();
        for (var entry : committedFutures.entrySet()) {
            committedByGroup.put(entry.getKey(), entry.getValue().get(2, TimeUnit.SECONDS));
        }

        Map<String, ConsumerGroupDescription> groupDescriptions = groupDescribeFuture == null
                ? Map.of()
                : groupDescribeFuture.get(2, TimeUnit.SECONDS);

        Map<String, TopicDescription> topicDescriptions = topicDescribeFuture.get(2, TimeUnit.SECONDS);

        var owned = processor.getOwnedPartitions();
        Map<String, Map<Integer, Long>> newPrevEndOffsets = new HashMap<>();
        List<TopicLag> topicResults = new ArrayList<>(TOPICS.size());

        for (TopicConfig tc : TOPICS) {
            boolean hasConsumer = tc.consumerGroup != null;
            Map<TopicPartition, org.apache.kafka.clients.consumer.OffsetAndMetadata> committedResult =
                    hasConsumer ? committedByGroup.getOrDefault(tc.consumerGroup, Map.of()) : Map.of();

            TopicDescription topicDescription = topicDescriptions.get(tc.topic);
            Map<Integer, TopicPartitionInfo> partitionInfoByIndex = new HashMap<>();
            if (topicDescription != null) {
                for (TopicPartitionInfo info : topicDescription.partitions()) {
                    partitionInfoByIndex.put(info.partition(), info);
                }
            }

            Map<Integer, Long> prevForTopic = prevEndOffsets.getOrDefault(tc.topic, Map.of());
            Map<Integer, Long> newPrevForTopic = new HashMap<>();

            List<PartitionDetail> partitionDetails = new ArrayList<>(tc.partitions);
            long totalLag = 0;
            long totalEndOffsetDelta = 0;
            int underReplicatedCount = 0;

            for (int p = 0; p < tc.partitions; p++) {
                TopicPartition tp = new TopicPartition(tc.topic, p);
                long endOffset = endOffsets.containsKey(tp) ? endOffsets.get(tp).offset() : 0L;
                var committed = committedResult.get(tp);
                long committedOffset = committed != null ? committed.offset() : 0L;
                long lag = hasConsumer ? Math.max(0, endOffset - committedOffset) : endOffset;

                boolean partitionOwned = tc.partitionSplit ? owned.contains(p) : true;

                TopicPartitionInfo info = partitionInfoByIndex.get(p);
                int isrCount = info != null ? info.isr().size() : -1;
                int replicaCount = info != null ? info.replicas().size() : -1;
                boolean underReplicated = info != null && isrCount < replicaCount;
                if (underReplicated) underReplicatedCount++;

                PartitionDetail detail = new PartitionDetail();
                detail.partition = p;
                detail.endOffset = endOffset;
                detail.committedOffset = committedOffset;
                detail.lag = lag;
                detail.owned = partitionOwned;
                detail.isrCount = isrCount;
                detail.replicaCount = replicaCount;
                detail.underReplicated = underReplicated;
                detail.logDirBytes = logDirBytesCache.getOrDefault(tp, -1L);
                partitionDetails.add(detail);

                totalLag += lag;
                Long prevOffset = prevForTopic.get(p);
                if (prevOffset != null) {
                    totalEndOffsetDelta += Math.max(0, endOffset - prevOffset);
                }
                newPrevForTopic.put(p, endOffset);
            }
            newPrevEndOffsets.put(tc.topic, newPrevForTopic);

            ConsumerGroupDescription groupDescription = hasConsumer ? groupDescriptions.get(tc.consumerGroup) : null;
            String groupState = !hasConsumer ? "NO_CONSUMER"
                    : groupDescription != null ? groupDescription.state().toString() : "UNKNOWN";
            int memberCount = groupDescription != null ? groupDescription.members().size() : 0;

            TopicLag topicLag = new TopicLag();
            topicLag.topic = tc.topic;
            topicLag.partitionCount = tc.partitions;
            topicLag.consumerGroup = tc.consumerGroup;
            topicLag.hasConsumer = hasConsumer;
            topicLag.groupState = groupState;
            topicLag.memberCount = memberCount;
            topicLag.totalLag = totalLag;
            topicLag.msgsPerSec = intervalSecs > 0 ? totalEndOffsetDelta / intervalSecs : 0;
            topicLag.underReplicatedCount = underReplicatedCount;
            topicLag.partitions = partitionDetails;
            topicResults.add(topicLag);
        }

        cachedTopics = topicResults;
        prevEndOffsets = newPrevEndOffsets;
        prevRefreshMs = nowMs;
    }

    private void refreshLogDirs() {
        try {
            Set<Integer> brokerIds = new HashSet<>();
            for (var node : adminClient.describeCluster().nodes().get(2, TimeUnit.SECONDS)) {
                brokerIds.add(node.id());
            }
            var logDirsFuture = adminClient.describeLogDirs(brokerIds).allDescriptions();
            var logDirsByBroker = logDirsFuture.get(3, TimeUnit.SECONDS);

            Map<TopicPartition, Long> sizes = new HashMap<>();
            for (var brokerEntry : logDirsByBroker.values()) {
                for (var dirEntry : brokerEntry.values()) {
                    for (var replicaEntry : dirEntry.replicaInfos().entrySet()) {
                        sizes.merge(replicaEntry.getKey(), replicaEntry.getValue().size(), Long::sum);
                    }
                }
            }
            logDirBytesCache = sizes;
            Log.debugf("Kafka log dir sizes refreshed for %d partitions", sizes.size());
        } catch (Exception e) {
            Log.debugf("Kafka log dir refresh failed (non-critical): %s", e.getMessage());
        }
    }

    public static class PartitionLag {
        public int partition;
        public long lag;
        public boolean owned;

        public PartitionLag() {}

        public PartitionLag(int partition, long lag, boolean owned) {
            this.partition = partition;
            this.lag = lag;
            this.owned = owned;
        }
    }

    public static class PartitionDetail {
        public int partition;
        public long endOffset;
        public long committedOffset;
        public long lag;
        public boolean owned;
        public int isrCount;
        public int replicaCount;
        public boolean underReplicated;
        public long logDirBytes = -1;
    }

    public static class TopicLag {
        public String topic;
        public int partitionCount;
        public String consumerGroup;
        public boolean hasConsumer;
        public String groupState;
        public int memberCount;
        public long totalLag;
        public double msgsPerSec;
        public int underReplicatedCount;
        public List<PartitionDetail> partitions;
    }

    private static class TopicConfig {
        final String topic;
        final int partitions;
        final String consumerGroup;
        final boolean partitionSplit;

        TopicConfig(String topic, int partitions, String consumerGroup, boolean partitionSplit) {
            this.topic = topic;
            this.partitions = partitions;
            this.consumerGroup = consumerGroup;
            this.partitionSplit = partitionSplit;
        }
    }
}
