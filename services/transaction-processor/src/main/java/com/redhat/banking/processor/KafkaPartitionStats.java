package com.redhat.banking.processor;

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
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class KafkaPartitionStats {

    private static final String TOPIC = "transactions-raw";
    private static final int PARTITIONS = 6;
    private static final long CACHE_TTL_MS = 2000;

    @Inject
    TransactionProcessor processor;

    private AdminClient adminClient;
    private final String consumerGroup =
            "transaction-processors-" + System.getenv().getOrDefault("SOURCE_CLUSTER", "unknown");

    private volatile List<PartitionLag> cached = List.of();
    private volatile long cacheTime = 0;

    @PostConstruct
    void init() {
        String bootstrap = System.getenv().getOrDefault(
                "KAFKA_BOOTSTRAP_SERVERS", "banking-kafka-kafka-bootstrap.banking-infra.svc.cluster.local:9092");
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "3000");
        props.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "4000");
        adminClient = AdminClient.create(props);
    }

    @PreDestroy
    void close() {
        if (adminClient != null) adminClient.close();
    }

    public List<PartitionLag> getLag() {
        long now = System.currentTimeMillis();
        if (now - cacheTime < CACHE_TTL_MS) return cached;

        try {
            Map<TopicPartition, OffsetSpec> latestSpecs = new HashMap<>();
            for (int p = 0; p < PARTITIONS; p++) {
                latestSpecs.put(new TopicPartition(TOPIC, p), OffsetSpec.latest());
            }

            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> endOffsets =
                    adminClient.listOffsets(latestSpecs).all().get(4, TimeUnit.SECONDS);

            var committedResult = adminClient.listConsumerGroupOffsets(consumerGroup)
                    .partitionsToOffsetAndMetadata().get(4, TimeUnit.SECONDS);

            var owned = processor.getOwnedPartitions();
            List<PartitionLag> result = new ArrayList<>(PARTITIONS);
            for (int p = 0; p < PARTITIONS; p++) {
                TopicPartition tp = new TopicPartition(TOPIC, p);
                long endOffset = endOffsets.containsKey(tp) ? endOffsets.get(tp).offset() : 0L;
                var committed = committedResult.get(tp);
                long committedOffset = committed != null ? committed.offset() : 0L;
                long lag = Math.max(0, endOffset - committedOffset);
                result.add(new PartitionLag(p, lag, owned.contains(p)));
            }

            cached = result;
            cacheTime = now;
            return result;
        } catch (Exception e) {
            return cached; // return last-known values on transient failure
        }
    }

    public record PartitionLag(int partition, long lag, boolean owned) {}
}
