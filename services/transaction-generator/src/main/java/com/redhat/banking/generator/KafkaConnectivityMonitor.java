package com.redhat.banking.generator;

import io.quarkus.logging.Log;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;

import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

// Consistent, low-noise, transition-only Kafka connectivity logging — same pattern as
// DatabaseConnectivityMonitor in account-service/ledger-service/transaction-processor,
// adapted to probe the transactions-raw topic instead of a JDBC connection. Complements
// (does not replace) the default SmallRye Reactive Messaging Kafka producer health
// check, which drives K8s readiness but only logs on every failed probe, not on
// transitions. This logs exactly once when the topic becomes unreachable and once when
// it's restored, with the actual downtime duration.
@Startup
@ApplicationScoped
public class KafkaConnectivityMonitor {

    private static final String TOPIC = "transactions-raw";

    private AdminClient adminClient;
    private ScheduledExecutorService scheduler;
    private String bootstrap;

    private final AtomicBoolean up = new AtomicBoolean(true);
    private final AtomicLong downSinceMs = new AtomicLong(0);

    @PostConstruct
    void init() {
        bootstrap = System.getenv().getOrDefault("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092");
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "2000");
        props.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "4000");
        adminClient = AdminClient.create(props);

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "kafka-connectivity-monitor");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::check, 5, 5, TimeUnit.SECONDS);
    }

    @PreDestroy
    void close() {
        if (scheduler != null) scheduler.shutdownNow();
        if (adminClient != null) adminClient.close();
    }

    private void check() {
        boolean healthy;
        String cause = null;
        try {
            adminClient.describeTopics(Set.of(TOPIC)).allTopicNames().get(2, TimeUnit.SECONDS);
            healthy = true;
        } catch (Exception e) {
            healthy = false;
            cause = e.getMessage();
        }

        if (healthy && !up.get()) {
            up.set(true);
            long since = downSinceMs.get();
            long downtimeSeconds = since > 0 ? (System.currentTimeMillis() - since) / 1000 : 0;
            Log.infof("Kafka topic '%s' AVAILABLE again (bootstrap=%s) after %ds", TOPIC, bootstrap, downtimeSeconds);
        } else if (!healthy && up.get()) {
            up.set(false);
            downSinceMs.set(System.currentTimeMillis());
            Log.warnf("Kafka topic '%s' UNAVAILABLE (bootstrap=%s): %s", TOPIC, bootstrap, cause);
        }
    }
}
