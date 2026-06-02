package com.redhat.banking.processor;

import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.Map;

@Path("/api/processor/stats")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class ProcessorStatsResource {

    @Inject
    TransactionProcessor processor;

    @GET
    @Blocking
    public Map<String, Object> stats() {
        return Map.of(
                "rejectedTotal",    processor.getRejectedCount(),
                "rejectedByReason", processor.getRejectedByReason()
        );
    }
}
