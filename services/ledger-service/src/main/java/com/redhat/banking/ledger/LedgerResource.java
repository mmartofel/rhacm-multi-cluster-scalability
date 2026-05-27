package com.redhat.banking.ledger;

import jakarta.inject.Inject;
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

    @GET
    @Path("/summary")
    public Response summary() {
        Long totalEntries = LedgerEntry.count();
        Object totalVolume = LedgerEntry.getEntityManager()
                .createNativeQuery("SELECT COALESCE(SUM(ABS(running_balance - LAG(running_balance, 1, running_balance) OVER (PARTITION BY account_id ORDER BY as_of))), 0) FROM ledger_entries")
                .getSingleResult();

        return Response.ok(Map.of(
                "cluster", System.getenv().getOrDefault("SOURCE_CLUSTER", "unknown"),
                "totalLedgerEntries", totalEntries,
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
