package com.redhat.banking.ledger;

import com.redhat.banking.TransactionCommitted;
import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.smallrye.mutiny.Uni;
import io.smallrye.reactive.messaging.annotations.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;

import org.hibernate.Session;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
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

    private static final String INSERT_SQL =
            "INSERT INTO ledger_entries (transaction_id, account_id, running_balance, as_of, source_cluster) "
            + "VALUES (?, ?, ?, ?, ?) ON CONFLICT (transaction_id) DO NOTHING";

    @Inject
    KafkaConsumerHealthState healthState;

    private final AtomicLong processedCount = new AtomicLong(0);

    // Batch mode (committed-in.batch=true): one DB transaction per Kafka poll instead of
    // per record. Confirmed live (2026-09-29): one-transaction-per-record meant >=2 DB
    // round trips per message, which on cloud go through RHSI to onprem PostgreSQL —
    // capping the single ledger consumer at ~75 msg/s against ~137 msg/s produced, so
    // ledger-updaters-cloud lag grew unbounded (~290k) with the pod's CPU nearly idle.
    // Latency-bound, not partition-bound: more partitions wouldn't help a single pod.
    @Incoming("committed-in")
    @Blocking
    public void onCommitted(List<TransactionCommitted> events) {
        if (events.isEmpty()) {
            return;
        }
        // One JDBC batch of ON CONFLICT DO NOTHING inserts keyed on the transaction id
        // (issue #21): a batch that PostgreSQL committed but whose reply was lost when the
        // interconnect dropped used to be inserted a second time by the retry below.
        Uni.createFrom().item(() -> QuarkusTransaction.requiringNew().call(() -> {
            LedgerEntry.getEntityManager().unwrap(Session.class).doWork(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(INSERT_SQL)) {
                    for (TransactionCommitted event : events) {
                        ps.setObject(1, UUID.fromString(event.getTransactionId()));
                        ps.setString(2, event.getAccountId());
                        ps.setBigDecimal(3, BigDecimal.valueOf(event.getBalanceAfter()));
                        ps.setTimestamp(4, Timestamp.from(event.getProcessedAt()));
                        ps.setString(5, event.getSourceCluster());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            });
            return null;
        }))
                .onFailure().retry().withBackOff(INITIAL_BACKOFF, MAX_BACKOFF).expireIn(RETRY_BUDGET.toMillis())
                .onFailure().invoke(failure -> {
                    Log.errorf(failure, "Giving up on persisting a batch of %d ledger entries after retrying for "
                            + "%ds — marking the Kafka consumer channel unhealthy so the pod restarts and "
                            + "redelivers this (never-acked) batch", events.size(), RETRY_BUDGET.getSeconds());
                    healthState.markChannelFailed("committed-in", rootCause(failure));
                })
                .await().indefinitely();

        processedCount.addAndGet(events.size());
        Log.debugf("Ledger updated: %d entries", events.size());
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
