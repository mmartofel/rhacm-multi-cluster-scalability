package com.redhat.banking.processor;

import io.quarkus.logging.Log;
import io.quarkus.runtime.Startup;
import io.smallrye.reactive.messaging.kafka.KafkaClientService;
import io.smallrye.reactive.messaging.kafka.KafkaConsumer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

// Second source for KafkaConsumerLivenessCheck (issue #24). KafkaConsumerHealthState is
// only set when OUR retry code gives up; any other fatal channel failure (e.g. the
// 2026-10-06 SRMSG18231/SRMSG18228 TooManyMessagesWithoutAckException) left the consumer
// dead with liveness still UP. Every fatal failure ends the same way inside SmallRye
// (KafkaSource.reportFailure(fatal) -> ReactiveKafkaConsumer.close(): Kafka consumer
// closed, polling executor shut down), so this probes exactly that: can a task still
// run on the consumer's polling thread?
//
// A healthy consumer answers between polls within about a second, with or without
// traffic and during rebalances. One failed probe means nothing — the polling thread is
// legitimately blocked while RetryingAvroDeserializationFailureHandler retries (up to 4
// minutes) — so the consumer only counts as dead once no probe has succeeded for
// kafka.consumer.liveness.stale-after, which must stay above that retry budget.
@Startup
@ApplicationScoped
public class KafkaConsumerPollMonitor {

    private static final String CHANNEL = "transactions-in";
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(5);

    @Inject
    KafkaClientService kafkaClientService;

    @ConfigProperty(name = "kafka.consumer.liveness.stale-after", defaultValue = "6M")
    Duration staleAfter;

    private ScheduledExecutorService scheduler;
    private volatile long lastOkMs = System.currentTimeMillis();
    private volatile int assignedPartitions = -1;
    private final AtomicBoolean responding = new AtomicBoolean(true);
    private final AtomicBoolean staleLogged = new AtomicBoolean(false);

    @PostConstruct
    void init() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "kafka-consumer-poll-monitor");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::check, 10, 10, TimeUnit.SECONDS);
    }

    @PreDestroy
    void close() {
        if (scheduler != null) scheduler.shutdownNow();
    }

    boolean isStale() {
        return secondsSinceLastPoll() > staleAfter.getSeconds();
    }

    long secondsSinceLastPoll() {
        return (System.currentTimeMillis() - lastOkMs) / 1000;
    }

    int getAssignedPartitions() {
        return assignedPartitions;
    }

    private void check() {
        String cause = null;
        try {
            KafkaConsumer<Object, Object> consumer = kafkaClientService.getConsumer(CHANNEL);
            if (consumer == null) {
                cause = "no consumer registered for the channel";
            } else {
                assignedPartitions = consumer.getAssignments().await().atMost(PROBE_TIMEOUT).size();
            }
        } catch (Exception e) {
            cause = e.getClass().getSimpleName() + ": " + e.getMessage();
        }

        if (cause == null) {
            long silentFor = secondsSinceLastPoll();
            lastOkMs = System.currentTimeMillis();
            staleLogged.set(false);
            if (responding.compareAndSet(false, true)) {
                Log.infof("Kafka consumer polling thread RESPONDING again (channel=%s) after %ds", CHANNEL, silentFor);
            }
            return;
        }

        if (responding.compareAndSet(true, false)) {
            Log.warnf("Kafka consumer polling thread NOT RESPONDING (channel=%s): %s", CHANNEL, cause);
        }
        if (isStale() && staleLogged.compareAndSet(false, true)) {
            Log.errorf("Kafka consumer polling thread unresponsive for %ds (channel=%s, limit %ds): %s — "
                    + "marking liveness DOWN so the pod restarts and a fresh consumer rejoins the group",
                    secondsSinceLastPoll(), CHANNEL, staleAfter.getSeconds(), cause);
        }
    }
}
