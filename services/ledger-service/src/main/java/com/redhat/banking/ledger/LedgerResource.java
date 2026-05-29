package com.redhat.banking.ledger;

import io.quarkus.scheduler.Scheduled;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

@Path("/api/ledger")
@Produces(MediaType.APPLICATION_JSON)
public class LedgerResource {

    @Inject
    LedgerUpdater updater;

    private volatile long cachedTotalEntries = 0;

    // Refresh the DB count on a worker thread every 2 s so that the summary
    // endpoint never competes with Kafka consumers for pool connections.
    @Scheduled(every = "PT2S")
    @Transactional
    void refreshCount() {
        cachedTotalEntries = LedgerEntry.count();
    }

    @GET
    @Path("/summary")
    public Response summary() {
        return Response.ok(Map.of(
                "cluster", System.getenv().getOrDefault("SOURCE_CLUSTER", "unknown"),
                "totalLedgerEntries", cachedTotalEntries,
                "processedSinceStart", updater.getProcessedCount(),
                "status", "ok"
        )).build();
    }

    @GET
    @Path("/health")
    public Response health() {
        return Response.ok(Map.of("status", "ok")).build();
    }
}
