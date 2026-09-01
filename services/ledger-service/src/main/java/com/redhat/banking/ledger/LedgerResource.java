package com.redhat.banking.ledger;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;

@Path("/api/ledger")
@Produces(MediaType.APPLICATION_JSON)
public class LedgerResource {

    @Inject
    LedgerUpdater updater;

    @Inject
    @DataSource("read")
    AgroalDataSource readDataSource;

    private volatile long cachedTotalEntries = 0;

    // Refresh the DB count via read datasource (cloud-local replica,
    // same datasource as write on onprem) to avoid Skupper round-trips.
    @Scheduled(every = "PT2S")
    void refreshCount() {
        try (Connection conn = readDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM ledger_entries");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) cachedTotalEntries = rs.getLong(1);
        } catch (Exception e) {
            Log.errorf("Failed to refresh ledger count from read replica: %s", e.getMessage());
        }
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
