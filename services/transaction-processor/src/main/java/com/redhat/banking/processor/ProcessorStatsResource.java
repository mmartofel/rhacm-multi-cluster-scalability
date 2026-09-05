package com.redhat.banking.processor;

import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.Map;

import com.redhat.banking.processor.KafkaPartitionStats.PartitionLag;
import com.redhat.banking.processor.KafkaPartitionStats.TopicLag;

@Path("/api/processor/stats")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class ProcessorStatsResource {

    @Inject
    TransactionProcessor processor;

    @Inject
    KafkaPartitionStats kafkaPartitionStats;

    @GET
    @Blocking
    public Map<String, Object> stats() {
        return Map.of(
                "rejectedTotal",    processor.getRejectedCount(),
                "rejectedByReason", processor.getRejectedByReason()
        );
    }

    @GET
    @Path("/partition-lag")
    @Blocking
    public List<PartitionLag> partitionLag() {
        return kafkaPartitionStats.getLag();
    }

    @GET
    @Path("/kafka-topics")
    @Blocking
    public List<TopicLag> kafkaTopics() {
        return kafkaPartitionStats.getTopicStats();
    }
}
