package com.redhat.banking.generator;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

// Replaces the readiness half of SmallRye Reactive Messaging's built-in Kafka health
// (disabled via quarkus.messaging.health.enabled=false, which also removes it from
// liveness — see application.properties). Readiness still reflects whether the
// transactions-raw topic is reachable, backed by KafkaConnectivityMonitor's 5s poll,
// so this is a cached read and never blocks the probe.
@Readiness
@ApplicationScoped
public class KafkaReadinessCheck implements HealthCheck {

    @Inject
    KafkaConnectivityMonitor monitor;

    @Override
    public HealthCheckResponse call() {
        return monitor.isUp()
                ? HealthCheckResponse.up("kafka-topic")
                : HealthCheckResponse.down("kafka-topic");
    }
}
