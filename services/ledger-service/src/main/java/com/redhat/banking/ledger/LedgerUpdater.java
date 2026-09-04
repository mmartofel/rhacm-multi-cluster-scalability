package com.redhat.banking.ledger;

import com.redhat.banking.TransactionCommitted;
import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.smallrye.mutiny.Uni;
import io.smallrye.reactive.messaging.annotations.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

@ApplicationScoped
public class LedgerUpdater {

    // A DB call thrown uncaught out of an @Incoming method invokes SmallRye's default
    // failure-strategy (KafkaFailStop, fatal=true) and permanently tears down the Kafka
    // consumer — confirmed live (2026-09-04): this persist() call ran an unguarded
    // QuarkusTransaction, and an RHSI interconnect restore's transient
    // "connection has been closed"/RollbackException window was enough to kill it.
    // ledger-service runs a single pod (no HPA), so this alone stopped ALL cloud ledger
    // updates permanently — "ledger-updaters-cloud has no active members" confirmed via
    // kafka-consumer-groups.sh, lag climbing unbounded, ~18 minutes after DB connectivity
    // itself had already logged RESTORED. quarkus.messaging.health.enabled=false plus
    // KafkaConsumerLivenessCheck's narrow deserialization-only scope meant nothing ever
    // noticed — the pod stayed 1/1 Ready throughout. Wrapping the persist with the same
    // retry-with-backoff-then-mark-unhealthy pattern already used by
    // RetryingAvroDeserializationFailureHandler lets a transient blip self-heal (via the
    // JDBC socketTimeout/connectTimeout fix) instead of killing the consumer outright.
    private static final Duration INITIAL_BACKOFF = Duration.ofSeconds(1);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(15);
    private static final Duration RETRY_BUDGET = Duration.ofMinutes(4);

    @Inject
    KafkaConsumerHealthState healthState;

    private final AtomicLong processedCount = new AtomicLong(0);

    @Incoming("committed-in")
    @Blocking
    public void onCommitted(TransactionCommitted event) {
        Uni.createFrom().item(() -> QuarkusTransaction.requiringNew().call(() -> {
            LedgerEntry entry = new LedgerEntry();
            entry.accountId = event.getAccountId();
            entry.runningBalance = BigDecimal.valueOf(event.getBalanceAfter());
            entry.asOf = event.getProcessedAt();
            entry.sourceCluster = event.getSourceCluster();
            entry.persist();
            return null;
        }))
                .onFailure().retry().withBackOff(INITIAL_BACKOFF, MAX_BACKOFF).expireIn(RETRY_BUDGET.toMillis())
                .onFailure().invoke(failure -> {
                    Log.errorf(failure, "Giving up on persisting a ledger entry for account %s after retrying for "
                            + "%ds — marking the Kafka consumer channel unhealthy so the pod restarts and "
                            + "redelivers this (never-acked) record", event.getAccountId(), RETRY_BUDGET.getSeconds());
                    healthState.markChannelFailed("committed-in", rootCause(failure));
                })
                .await().indefinitely();

        processedCount.incrementAndGet();
        Log.debugf("Ledger updated: account=%s balance=%.2f cluster=%s",
                event.getAccountId(), event.getBalanceAfter(), event.getSourceCluster());
    }

    public long getProcessedCount() {
        return processedCount.get();
    }

    private static String rootCause(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null) {
            cur = cur.getCause();
        }
        return cur.getClass().getSimpleName() + ": " + cur.getMessage();
    }
}
