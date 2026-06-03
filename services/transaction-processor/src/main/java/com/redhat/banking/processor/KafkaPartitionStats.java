package com.redhat.banking.processor;

import io.quarkus.logging.Log;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.common.TopicPartition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Startup
@ApplicationScoped
public class KafkaPartitionStats {

    private static final String TOPIC = "transactions-raw";
    private static final int PARTITIONS = 6;

    @Inject
    TransactionProcessor processor;

    private AdminClient adminClient;
    private ScheduledExecutorService scheduler;

    private final String consumerGroup =
            "transaction-processors-" + System.getenv().getOrDefault("SOURCE_CLUSTER", "unknown");

    private volatile List<PartitionLag> cached = List.of();

    @PostConstruct
    void init() {
        String bootstrap = System.getenv().getOrDefault(
                "KAFKA_BOOTSTRAP_SERVERS", "banking-kafka-kafka-bootstrap.banking-infra.svc.cluster.local:9092");
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "2000");
        props.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "3000");
        adminClient = AdminClient.create(props);
        Log.infof("KafkaPartitionStats initialized — group=%s bootstrap=%s", consumerGroup, bootstrap);

        // Proactively refresh cache every 3 s — decoupled from HTTP request path
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "kafka-lag-refresher");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::refreshCache, 1, 3, TimeUnit.SECONDS);
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

    private void refreshCache() {
        try {
            Map<TopicPartition, OffsetSpec> latestSpecs = new HashMap<>();
            for (int p = 0; p < PARTITIONS; p++) {
                latestSpecs.put(new TopicPartition(TOPIC, p), OffsetSpec.latest());
            }

            // Launch both futures concurrently before waiting on either
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
        } catch (Exception e) {
            Log.warnf("Partition lag refresh failed: %s", e.getMessage());
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
}
