package com.redhat.banking.ledger;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.logging.Log;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

// Consistent, low-noise, transition-only DB connectivity logging. Complements (does not
// replace) the SmallRye readiness check, which still correctly drives K8s NotReady/
// routing — SmallRye Health only logs "Reporting health down status" on every readiness
// probe hit (~every 10s) for as long as it stays down; this logs exactly once when
// connectivity is lost and once when it's restored, with the actual downtime duration.
@Startup
@ApplicationScoped
public class DatabaseConnectivityMonitor {

    @Inject
    AgroalDataSource dataSource;

    @Inject
    @DataSource("read")
    AgroalDataSource readDataSource;

    private ScheduledExecutorService scheduler;
    private final Map<String, AtomicBoolean> up = new LinkedHashMap<>();
    private final Map<String, AtomicLong> downSinceMs = new LinkedHashMap<>();

    @PostConstruct
    void init() {
        up.put("default", new AtomicBoolean(true));
        up.put("read", new AtomicBoolean(true));
        downSinceMs.put("default", new AtomicLong(0));
        downSinceMs.put("read", new AtomicLong(0));

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "db-connectivity-monitor");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::checkAll, 5, 5, TimeUnit.SECONDS);
    }

    @PreDestroy
    void close() {
        if (scheduler != null) scheduler.shutdownNow();
    }

    private void checkAll() {
        check("default", dataSource);
        check("read", readDataSource);
    }

    private void check(String name, AgroalDataSource ds) {
        boolean healthy;
        String cause = null;
        try (Connection c = ds.getConnection()) {
            healthy = c.isValid(2);
        } catch (Exception e) {
            healthy = false;
            cause = e.getMessage();
        }

        AtomicBoolean wasUp = up.get(name);
        if (healthy && !wasUp.get()) {
            wasUp.set(true);
            long since = downSinceMs.get(name).get();
            long downtimeSeconds = since > 0 ? (System.currentTimeMillis() - since) / 1000 : 0;
            Log.infof("Database connectivity RESTORED (datasource=%s) after %ds", name, downtimeSeconds);
        } else if (!healthy && wasUp.get()) {
            wasUp.set(false);
            downSinceMs.get(name).set(System.currentTimeMillis());
            Log.warnf("Database connectivity LOST (datasource=%s): %s", name, cause);
        }
    }
}
