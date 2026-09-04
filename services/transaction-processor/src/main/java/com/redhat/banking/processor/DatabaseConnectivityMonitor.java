package com.redhat.banking.processor;

import io.agroal.api.AgroalDataSource;
import io.quarkus.logging.Log;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

// Consistent, low-noise, transition-only DB connectivity logging (same pattern as
// account-service/ledger-service; this service has no named "read" datasource).
// Complements (does not replace) the SmallRye readiness check, which still correctly
// drives K8s NotReady/routing — SmallRye Health only logs "Reporting health down
// status" on every readiness probe hit (~every 10s) for as long as it stays down; this
// logs exactly once when connectivity is lost and once when it's restored, with the
// actual downtime duration.
@Startup
@ApplicationScoped
public class DatabaseConnectivityMonitor {

    @Inject
    AgroalDataSource dataSource;

    private ScheduledExecutorService scheduler;
    private final AtomicBoolean up = new AtomicBoolean(true);
    private final AtomicLong downSinceMs = new AtomicLong(0);

    @PostConstruct
    void init() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "db-connectivity-monitor");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::check, 5, 5, TimeUnit.SECONDS);
    }

    @PreDestroy
    void close() {
        if (scheduler != null) scheduler.shutdownNow();
    }

    private void check() {
        boolean healthy;
        String cause = null;
        try (Connection c = dataSource.getConnection()) {
            healthy = c.isValid(2);
        } catch (Exception e) {
            healthy = false;
            cause = e.getMessage();
        }

        if (healthy && !up.get()) {
            up.set(true);
            long since = downSinceMs.get();
            long downtimeSeconds = since > 0 ? (System.currentTimeMillis() - since) / 1000 : 0;
            Log.infof("Database connectivity RESTORED (datasource=default) after %ds", downtimeSeconds);
        } else if (!healthy && up.get()) {
            up.set(false);
            downSinceMs.set(System.currentTimeMillis());
            Log.warnf("Database connectivity LOST (datasource=default): %s", cause);
        }
    }
}
