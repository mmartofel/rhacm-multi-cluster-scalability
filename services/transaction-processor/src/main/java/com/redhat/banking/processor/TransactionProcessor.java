package com.redhat.banking.processor;

import com.redhat.banking.TransactionCommitted;
import com.redhat.banking.TransactionEvent;
import com.redhat.banking.TransactionFailed;
import com.redhat.banking.TransactionType;
import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.smallrye.mutiny.Uni;
import io.smallrye.reactive.messaging.annotations.Blocking;
import io.smallrye.reactive.messaging.kafka.api.IncomingKafkaRecordMetadata;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.WebApplicationException;
import org.eclipse.microprofile.reactive.messaging.Acknowledgment;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@ApplicationScoped
public class TransactionProcessor {

    // A DB call thrown uncaught out of an @Incoming method invokes SmallRye's default
    // failure-strategy (KafkaFailStop, fatal=true) and permanently tears down this
    // pod's Kafka consumer — confirmed live (2026-09-04): the idempotency pre-check and
    // the commit INSERT below both ran unguarded QuarkusTransaction calls, and an RHSI
    // interconnect restore's transient "connection has been closed"/JDBCConnectionException
    // window was enough to kill the consumer of 2 of 4 transaction-processor pods (and,
    // worse, ledger-service's ONLY pod, see LedgerUpdater) with zero further consumption
    // ever after, even though DatabaseConnectivityMonitor logged RESTORED ~2 minutes
    // later — quarkus.messaging.health.enabled=false plus KafkaConsumerLivenessCheck's
    // narrow deserialization-only scope meant nothing ever noticed. Wrapping both DB
    // calls with the same retry-with-backoff-then-mark-unhealthy pattern already used by
    // RetryingAvroDeserializationFailureHandler lets a transient blip self-heal (via the
    // same JDBC socketTimeout/connectTimeout fix) instead of killing the consumer on the
    // very first attempt.
    // (Since issues #20/#21 the commit INSERT is done by account-service inside its
    // idempotent apply; only the pre-check below still runs here.)
    //
    // Since issue #25 the call to account-service goes through the same wrapper: a failed
    // apply is retried until it gets an answer instead of being sent to the DLQ as
    // "service error" (safe, the apply is idempotent on the transaction id). The DLQ now
    // receives only what account-service itself rejects — insufficient funds, unknown
    // account. The processor also no longer sends an account version: every account is
    // written by one processor on each cluster, so the cached version was stale about
    // every second apply and the "version conflict" answer it produced was 94% of the
    // DLQ, while the check added nothing — the balance UPDATE is a relative delta, atomic,
    // guarded against going negative and idempotent.
    private static final Duration INITIAL_BACKOFF = Duration.ofSeconds(1);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(15);
    private static final Duration RETRY_BUDGET = Duration.ofMinutes(4);

    @Inject
    @RestClient
    AccountServiceClient accountClient;

    @Inject
    @Channel("transactions-committed-out")
    Emitter<TransactionCommitted> committedEmitter;

    @Inject
    @Channel("transactions-dlq-out")
    Emitter<TransactionFailed> dlqEmitter;

    @Inject
    EntityManager em;

    @Inject
    KafkaConsumerHealthState healthState;

    private final String sourceCluster = System.getenv().getOrDefault("SOURCE_CLUSTER", "unknown");

    private final AtomicReference<Set<Integer>> ownedPartitions = new AtomicReference<>(
            parseOwnedPartitions(System.getenv().getOrDefault("OWNED_PARTITIONS", "0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23")));

    // Rejected transaction counters — reset only on pod restart
    private final AtomicLong rejectedCount = new AtomicLong(0);
    private final ConcurrentHashMap<String, AtomicLong> rejectedByReason = new ConcurrentHashMap<>();

    private static Set<Integer> parseOwnedPartitions(String spec) {
        return Arrays.stream(spec.split(","))
                .map(String::trim).map(Integer::parseInt).collect(Collectors.toSet());
    }

    public Set<Integer> getOwnedPartitions() {
        return ownedPartitions.get();
    }

    public long getRejectedCount() {
        return rejectedCount.get();
    }

    public Map<String, Long> getRejectedByReason() {
        Map<String, Long> snapshot = new HashMap<>();
        rejectedByReason.forEach((k, v) -> snapshot.put(k, v.get()));
        return snapshot;
    }

    @Incoming("transactions-in")
    @Blocking
    @Acknowledgment(Acknowledgment.Strategy.MANUAL)
    public CompletionStage<Void> process(Message<TransactionEvent> message) {
        int partition = message.getMetadata(IncomingKafkaRecordMetadata.class)
                .map(IncomingKafkaRecordMetadata::getPartition)
                .orElse(-1);

        if (!ownedPartitions.get().contains(partition)) {
            return message.ack();
        }

        TransactionEvent event = message.getPayload();

        // Pre-check: already committed (Kafka redelivery). The earlier delivery may have
        // stopped between the apply and the emit — a pod scaled down while it waited for
        // account-service through an outage (seen 2026-10-08: two committed transactions
        // with no ledger entry) — so emit again; ledger-service ignores a repeated id.
        List<?> committedBalance = withDbRetry(() -> QuarkusTransaction.requiringNew().call(() ->
                em.createNativeQuery("SELECT balance_after FROM transactions WHERE transaction_id = ?1")
                        .setParameter(1, UUID.fromString(event.getTransactionId()))
                        .getResultList()));
        if (!committedBalance.isEmpty()) {
            Log.debugf("Transaction %s already committed (redelivery)", event.getTransactionId());
            Number balanceAfter = (Number) committedBalance.get(0);
            if (balanceAfter != null) {
                emitCommitted(event, balanceAfter.doubleValue());
            }
            return message.ack();
        }

        double delta = event.getType() == TransactionType.DEBIT
                ? -event.getAmount()
                : event.getAmount();

        ApplyResponse response = apply(event, delta);
        if (!response.success) {
            sendToDlq(event, response.reason);
            Log.warnf("Transaction %s sent to DLQ: %s", event.getTransactionId(), response.reason);
            return message.ack();
        }

        // account-service wrote the transactions row together with the balance update
        // (idempotent on the transaction id). A duplicate means an earlier delivery already
        // applied it but may have died before emitting — emit again; ledger-service
        // ignores a repeated transaction id.
        if (response.duplicate) {
            Log.debugf("Transaction %s already applied (duplicate delivery)", event.getTransactionId());
        }
        emitCommitted(event, response.newBalance);

        return message.ack();
    }

    private void emitCommitted(TransactionEvent event, double balanceAfter) {
        committedEmitter.send(TransactionCommitted.newBuilder()
                .setTransactionId(event.getTransactionId())
                .setAccountId(event.getAccountId())
                .setBalanceAfter(balanceAfter)
                .setProcessedAt(Instant.now())
                .setSourceCluster(sourceCluster)
                .build());
    }

    private void sendToDlq(TransactionEvent event, String reason) {
        rejectedCount.incrementAndGet();
        rejectedByReason.computeIfAbsent(reason, k -> new AtomicLong()).incrementAndGet();
        try {
            TransactionFailed failed = TransactionFailed.newBuilder()
                    .setTransactionId(event.getTransactionId())
                    .setAccountId(event.getAccountId())
                    .setType(event.getType().name())
                    .setAmount(event.getAmount())
                    .setFailureReason(reason)
                    .setFailedAt(Instant.now())
                    .setSourceCluster(sourceCluster)
                    .build();
            dlqEmitter.send(failed);
        } catch (Exception e) {
            Log.errorf("Failed to emit to DLQ for transaction %s: %s", event.getTransactionId(), e.getMessage());
        }
    }

    private <T> T withDbRetry(Supplier<T> dbCall) {
        return Uni.createFrom().item(dbCall)
                .onFailure().retry().withBackOff(INITIAL_BACKOFF, MAX_BACKOFF).expireIn(RETRY_BUDGET.toMillis())
                .onFailure().invoke(failure -> {
                    Log.errorf(failure, "Giving up on a transactions-in DB/account-service call after retrying for %ds — marking "
                            + "the Kafka consumer channel unhealthy so the pod restarts and redelivers this "
                            + "(never-acked) record", RETRY_BUDGET.getSeconds());
                    healthState.markChannelFailed("transactions-in", rootCause(failure));
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

    private ApplyResponse apply(TransactionEvent event, double delta) {
        ApplyRequest body = new ApplyRequest();
        body.delta = delta;
        body.transactionId = event.getTransactionId();
        body.type = event.getType().name();
        body.amount = event.getAmount();
        body.processedAt = event.getTimestamp().toEpochMilli();
        body.sourceCluster = sourceCluster;
        AtomicBoolean warned = new AtomicBoolean();
        return withDbRetry(() -> {
            try {
                return accountClient.applyDelta(event.getAccountId(), body);
            } catch (WebApplicationException e) {
                int status = e.getResponse() == null ? 500 : e.getResponse().getStatus();
                if (status >= 400 && status < 500) {
                    // account-service understood the request and refused it — repeating
                    // it cannot change the answer.
                    ApplyResponse rejected = new ApplyResponse();
                    rejected.accountId = event.getAccountId();
                    rejected.reason = status == 404 ? "account not found" : "rejected by account-service (HTTP " + status + ")";
                    return rejected;
                }
                warnOnce(warned, event, e);
                throw e;
            } catch (RuntimeException e) {
                warnOnce(warned, event, e);
                throw e;
            }
        });
    }

    private static void warnOnce(AtomicBoolean warned, TransactionEvent event, Exception e) {
        if (warned.compareAndSet(false, true)) {
            Log.warnf("Apply for account %s (transaction %s) failed, retrying until account-service answers: %s",
                    event.getAccountId(), event.getTransactionId(), e.getMessage());
        }
    }
}
