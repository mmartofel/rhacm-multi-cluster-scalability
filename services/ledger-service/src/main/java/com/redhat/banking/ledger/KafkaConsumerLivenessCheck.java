package com.redhat.banking.ledger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;

// Deliberately narrow and separate from SmallRye Reactive Messaging's own built-in
// Kafka @Liveness check (disabled via quarkus.messaging.health.enabled=false) — see
// KafkaConsumerHealthState for the full rationale. This only fails once the consumer
// channel has been confirmed permanently dead by RetryingAvroDeserializationFailureHandler,
// not on ordinary transient degradation, so it restarts the pod only when a restart is
// actually the only way to recover.
@Liveness
@ApplicationScoped
public class KafkaConsumerLivenessCheck implements HealthCheck {

    @Inject
    KafkaConsumerHealthState healthState;

    @Override
    public HealthCheckResponse call() {
        if (healthState.isHealthy()) {
            return HealthCheckResponse.up("kafka-consumer-channel");
        }
        return HealthCheckResponse.builder()
                .name("kafka-consumer-channel")
                .down()
                .withData("channel", healthState.getFailedChannel())
                .withData("reason", healthState.getFailureReason())
                .build();
    }
}
