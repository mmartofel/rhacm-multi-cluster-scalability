package com.redhat.banking.processor;

import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.redhat.banking.processor.KafkaPartitionStats.PartitionLag;

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

    @PUT
    @Path("/partitions")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response setPartitions(Map<String, List<Integer>> body) {
        List<Integer> list = body.getOrDefault("partitions", List.of());
        Set<Integer> partitions = list.stream().collect(Collectors.toSet());
        processor.setOwnedPartitions(partitions);
        return Response.ok(Map.of("ownedPartitions", list)).build();
    }
}
