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
import org.eclipse.microprofile.reactive.messaging.Acknowledgment;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
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

    private final ConcurrentHashMap<String, Long> accountVersionCache = new ConcurrentHashMap<>();

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

        // Pre-check: skip if already committed (Kafka redelivery after crash)
        boolean alreadyProcessed = withDbRetry(() -> QuarkusTransaction.requiringNew().call(() -> {
            Number count = (Number) em.createNativeQuery(
                    "SELECT COUNT(*) FROM transactions WHERE transaction_id = ?1")
                    .setParameter(1, UUID.fromString(event.getTransactionId()))
                    .getSingleResult();
            return count.longValue() > 0;
        }));
        if (alreadyProcessed) {
            Log.debugf("Transaction %s already committed (redelivery skip)", event.getTransactionId());
            return message.ack();
        }

        double delta = event.getType() == TransactionType.DEBIT
                ? -event.getAmount()
                : event.getAmount();

        ApplyResponse response = applyWithVersion(event.getAccountId(), delta);
        if (response == null) {
            sendToDlq(event, "service error");
            return message.ack();
        }

        if (!response.success) {
            if ("version conflict".equals(response.reason)) {
                accountVersionCache.put(event.getAccountId(), response.version);
                response = applyWithVersion(event.getAccountId(), delta);
                if (response == null || !response.success) {
                    sendToDlq(event, "version conflict");
                    Log.warnf("Transaction %s sent to DLQ after version conflict retry", event.getTransactionId());
                    return message.ack();
                }
            } else {
                sendToDlq(event, response.reason);
                Log.warnf("Transaction %s sent to DLQ: %s", event.getTransactionId(), response.reason);
                return message.ack();
            }
        }

        accountVersionCache.put(event.getAccountId(), response.version);

        final ApplyResponse finalResponse = response;
        boolean shouldEmit = withDbRetry(() -> QuarkusTransaction.requiringNew().call(() -> {
            int inserted = em.createNativeQuery(
                    "INSERT INTO transactions (transaction_id, account_id, type, amount, balance_after, processed_at, source_cluster) " +
                    "VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7) ON CONFLICT (transaction_id) DO NOTHING")
                    .setParameter(1, UUID.fromString(event.getTransactionId()))
                    .setParameter(2, event.getAccountId())
                    .setParameter(3, event.getType().name())
                    .setParameter(4, BigDecimal.valueOf(event.getAmount()))
                    .setParameter(5, BigDecimal.valueOf(finalResponse.newBalance))
                    .setParameter(6, event.getTimestamp())
                    .setParameter(7, sourceCluster)
                    .executeUpdate();

            if (inserted == 0) {
                Log.debugf("Transaction %s already committed (idempotent skip)", event.getTransactionId());
            }
            return inserted > 0;
        }));

        if (shouldEmit) {
            TransactionCommitted committed = TransactionCommitted.newBuilder()
                    .setTransactionId(event.getTransactionId())
                    .setAccountId(event.getAccountId())
                    .setBalanceAfter(response.newBalance)
                    .setProcessedAt(Instant.now())
                    .setSourceCluster(sourceCluster)
                    .build();
            committedEmitter.send(committed);
        }

        return message.ack();
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
                    Log.errorf(failure, "Giving up on a transactions-in DB call after retrying for %ds — marking "
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

    private ApplyResponse applyWithVersion(String accountId, double delta) {
        Long cachedVersion = accountVersionCache.get(accountId);
        Map<String, Number> body = new HashMap<>();
        body.put("delta", delta);
        if (cachedVersion != null) {
            body.put("version", cachedVersion);
        }
        try {
            return accountClient.applyDelta(accountId, body);
        } catch (Exception e) {
            Log.errorf("Failed to apply balance for account %s: %s", accountId, e.getMessage());
            return null;
        }
    }
}
