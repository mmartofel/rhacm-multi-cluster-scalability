package com.redhat.banking.processor;

import com.redhat.banking.TransactionCommitted;
import com.redhat.banking.TransactionEvent;
import com.redhat.banking.TransactionType;
import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.QuarkusTransaction;
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
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@ApplicationScoped
public class TransactionProcessor {

    @Inject
    @RestClient
    AccountServiceClient accountClient;

    @Inject
    @Channel("transactions-committed-out")
    Emitter<TransactionCommitted> committedEmitter;

    @Inject
    EntityManager em;

    private final String sourceCluster = System.getenv().getOrDefault("SOURCE_CLUSTER", "unknown");

    private final Set<Integer> ownedPartitions = parseOwnedPartitions(
            System.getenv().getOrDefault("OWNED_PARTITIONS", "0,1,2,3,4,5"));

    // Per-account version cache — avoids a round-trip GET before every apply
    private final ConcurrentHashMap<String, Long> accountVersionCache = new ConcurrentHashMap<>();

    private static Set<Integer> parseOwnedPartitions(String spec) {
        return Arrays.stream(spec.split(","))
                .map(String::trim).map(Integer::parseInt).collect(Collectors.toSet());
    }

    @Incoming("transactions-in")
    @Blocking
    @Acknowledgment(Acknowledgment.Strategy.MANUAL)
    public CompletionStage<Void> process(Message<TransactionEvent> message) {
        int partition = message.getMetadata(IncomingKafkaRecordMetadata.class)
                .map(IncomingKafkaRecordMetadata::getPartition)
                .orElse(-1);

        if (!ownedPartitions.contains(partition)) {
            return message.ack();
        }

        TransactionEvent event = message.getPayload();

        // Pre-check: skip if already committed — guards against Kafka redelivery
        // after a crash between the DB write and the offset commit.
        boolean alreadyProcessed = QuarkusTransaction.requiringNew().call(() -> {
            Number count = (Number) em.createNativeQuery(
                    "SELECT COUNT(*) FROM transactions WHERE transaction_id = ?1")
                    .setParameter(1, UUID.fromString(event.getTransactionId()))
                    .getSingleResult();
            return count.longValue() > 0;
        });
        if (alreadyProcessed) {
            Log.debugf("Transaction %s already committed (redelivery skip)", event.getTransactionId());
            return message.ack();
        }

        double delta = event.getType() == TransactionType.DEBIT
                ? -event.getAmount()
                : event.getAmount();

        ApplyResponse response = applyWithVersion(event.getAccountId(), delta);
        if (response == null) {
            return message.ack();
        }

        if (!response.success) {
            if ("version conflict".equals(response.reason)) {
                // Conflict response carries the current DB version — retry once with it
                accountVersionCache.put(event.getAccountId(), response.version);
                response = applyWithVersion(event.getAccountId(), delta);
                if (response == null || !response.success) {
                    Log.warnf("Transaction %s failed after version conflict retry: %s",
                            event.getTransactionId(), response != null ? response.reason : "error");
                    return message.ack();
                }
            } else {
                Log.warnf("Transaction %s rejected: %s", event.getTransactionId(), response.reason);
                return message.ack();
            }
        }

        // Cache the post-write version for the next transaction on this account
        accountVersionCache.put(event.getAccountId(), response.version);

        final ApplyResponse finalResponse = response;
        boolean shouldEmit = QuarkusTransaction.requiringNew().call(() -> {
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
        });

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
