package com.redhat.banking.processor;

import io.quarkus.logging.Log;
import io.smallrye.common.annotation.Identifier;
import io.smallrye.mutiny.Uni;
import io.smallrye.reactive.messaging.kafka.DeserializationFailureHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.kafka.common.header.Headers;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

// Confirmed live (2026-09-04 RHSI outage): without a DeserializationFailureHandler
// registered, SmallRye Reactive Messaging Kafka treats ANY value-deserialization
// failure as fatal — it logs SRMSG18249 ("configure a DeserializationFailureHandler to
// recover from errors"), revokes the consumer from its group, and never rejoins. The
// pod stays 1/1 Ready (DB-only readiness, see quarkus.messaging.health.enabled=false)
// with a permanently dead Kafka consumer — confirmed live, ~45 minutes of zero
// consumption on all 6 processor pods until manually restarted, all triggered by one
// transient "apicurio-registry...: Name or service not known" DNS blip through RHSI.
//
// This registers as mp.messaging.incoming.transactions-in.value-deserialization-failure-handler
// (see application.properties) and retries the deserialization (which re-attempts the
// Apicurio schema lookup — schemas are cached after the first successful resolution
// per apicurio.registry.check-period-ms, so only the first record hitting an
// uncached/new schema mapping ever pays this cost) with backoff for up to
// RETRY_BUDGET before giving up. Retrying re-invokes the exact same schema lookup the
// bare Kafka client already does — the fix is giving it time to succeed instead of
// dying on the first attempt, mirroring the JDBC socketTimeout/connectTimeout fix
// (Agroal self-heals a dead connection within the same acquisition attempt instead of
// needing a restart).
//
// RETRY_BUDGET (4 minutes) matches the sustained-outage duration already verified
// elsewhere in this codebase (transaction-generator's Apicurio check-period-ms fix was
// re-verified against a real 4-minute RHSI break with zero errors) — long enough to
// absorb the outages this system is actually tested against without ever restarting.
// If retries are exhausted (a longer outage, or a genuinely bad record), this marks
// KafkaConsumerHealthState and re-throws rather than silently skipping the record:
// the failing record's offset was never committed (MANUAL acknowledgment), so letting
// the channel fail-stop and the pod restart on the resulting liveness failure is safe
// — no data loss, just a bounded, visible recovery instead of an unbounded silent one.
@ApplicationScoped
@Identifier("retrying-avro-deserializer")
public class RetryingAvroDeserializationFailureHandler implements DeserializationFailureHandler<Object> {

    private static final Duration INITIAL_BACKOFF = Duration.ofSeconds(1);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(15);
    private static final Duration RETRY_BUDGET = Duration.ofMinutes(4);

    @Inject
    KafkaConsumerHealthState healthState;

    @Override
    public Object decorateDeserialization(Uni<Object> deserialize, String topic, boolean isKey,
            String deserializer, byte[] data, Headers headers) {
        AtomicBoolean loggedStart = new AtomicBoolean(false);

        return deserialize
                .onFailure().invoke(failure -> {
                    if (loggedStart.compareAndSet(false, true)) {
                        Log.warnf(failure, "Deserialization failing for topic '%s' (likely Apicurio/RHSI "
                                + "unreachable) — retrying with backoff for up to %ds before giving up",
                                topic, RETRY_BUDGET.getSeconds());
                    }
                })
                .onFailure().retry().withBackOff(INITIAL_BACKOFF, MAX_BACKOFF).expireIn(RETRY_BUDGET.toMillis())
                .onFailure().invoke(failure -> {
                    String reason = rootCause(failure);
                    Log.errorf(failure, "Giving up on deserializing a record from topic '%s' after retrying for "
                            + "%ds — marking the Kafka consumer channel unhealthy so the pod restarts and "
                            + "redelivers this (never-acked) record", topic, RETRY_BUDGET.getSeconds());
                    healthState.markChannelFailed(topic, reason);
                })
                .onItem().invoke(item -> {
                    if (loggedStart.get()) {
                        Log.infof("Deserialization RECOVERED for topic '%s'", topic);
                    }
                })
                .await().indefinitely();
    }

    private static String rootCause(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null) {
            cur = cur.getCause();
        }
        return cur.getClass().getSimpleName() + ": " + cur.getMessage();
    }
}
