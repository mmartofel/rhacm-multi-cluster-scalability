package com.redhat.banking.ledger;

import jakarta.enterprise.context.ApplicationScoped;

// Backs a narrow, custom @Liveness check (KafkaConsumerLivenessCheck) that is
// deliberately separate from SmallRye Reactive Messaging's own built-in Kafka health
// (disabled via quarkus.messaging.health.enabled=false, see application.properties) —
// that built-in check flips DOWN on any transient channel degradation (e.g. Apicurio
// briefly unreachable through the RHSI tunnel) even though the Kafka client reconnects
// on its own once the outage clears, causing restart storms for no benefit.
//
// This state is set ONLY by RetryingAvroDeserializationFailureHandler, and only after
// it has already retried deserialization with backoff for several minutes — i.e. only
// for a genuine permanent fail-stop of the consumer channel (confirmed live: without a
// DeserializationFailureHandler, a single deserialization failure — e.g. from a brief
// Apicurio DNS blip — makes SmallRye revoke the consumer from its group and never
// rejoin, silently starving the topic of consumption for as long as the pod lives).
// Once set, it never resets on its own: the channel really is dead, and the only fix
// is the pod restart this triggers, which lets a fresh consumer rejoin the group and
// redeliver the never-acked record (no data loss — MANUAL acknowledgment means the
// failed record's offset was never committed).
@ApplicationScoped
public class KafkaConsumerHealthState {

    private volatile String failedChannel;
    private volatile String failureReason;

    void markChannelFailed(String topic, String reason) {
        failedChannel = topic;
        failureReason = reason;
    }

    boolean isHealthy() {
        return failedChannel == null;
    }

    String getFailedChannel() {
        return failedChannel;
    }

    String getFailureReason() {
        return failureReason;
    }
}
