package com.redhat.banking.processor;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
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

    @Inject
    KafkaConsumerPollMonitor pollMonitor;

    @Override
    public HealthCheckResponse call() {
        HealthCheckResponseBuilder response = HealthCheckResponse.builder()
                .name("kafka-consumer-channel")
                .withData("assignedPartitions", pollMonitor.getAssignedPartitions())
                .withData("secondsSinceLastPoll", pollMonitor.secondsSinceLastPoll());
        if (!healthState.isHealthy()) {
            return response.down()
                    .withData("channel", healthState.getFailedChannel())
                    .withData("reason", healthState.getFailureReason())
                    .build();
        }
        // Any other fatal channel failure closes the consumer without going through
        // healthState — see KafkaConsumerPollMonitor.
        if (pollMonitor.isStale()) {
            return response.down()
                    .withData("reason", "consumer polling thread unresponsive for "
                            + pollMonitor.secondsSinceLastPoll() + "s")
                    .build();
        }
        return response.up().build();
    }
}
