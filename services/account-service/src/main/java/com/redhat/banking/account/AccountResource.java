package com.redhat.banking.account;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.cache.CacheInvalidate;
import io.quarkus.cache.CacheKey;
import io.quarkus.cache.CacheResult;
import io.quarkus.logging.Log;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.Query;
import jakarta.transaction.SystemException;
import jakarta.transaction.TransactionManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Path("/api/accounts")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class AccountResource {

    @Inject
    @DataSource("read")
    AgroalDataSource readDataSource;

    @GET
    @Path("/{accountId}/balance")
    @CacheResult(cacheName = "balance")
    @Blocking
    public Response getBalance(@PathParam("accountId") String accountId) {
        try (Connection conn = readDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT balance, version FROM accounts WHERE account_id = ?")) {
            ps.setString(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Response.status(Response.Status.NOT_FOUND).build();
                }
                return Response.ok(Map.of(
                        "accountId", accountId,
                        "balance",   rs.getBigDecimal(1),
                        "version",   rs.getLong(2)
                )).build();
            }
        } catch (Exception e) {
            Log.errorf("Read datasource error for account %s: %s", accountId, e.getMessage());
            throw new jakarta.ws.rs.InternalServerErrorException("Balance read unavailable: " + e.getMessage());
        }
    }

    @Inject
    TransactionManager transactionManager;

    // Idempotent on the transaction id (issues #20): the balance UPDATE and the
    // transactions row are written by ONE statement, and transactions.transaction_id is
    // the primary key. Before this, the row was inserted later by transaction-processor
    // in a separate DB transaction, so two overlapping deliveries of one Kafka message
    // (a rebalance while it was in flight, or an apply that finished after the caller's
    // timeout) both changed the balance. Now a repeat finds its INSERT rejected by the
    // key, and the whole DB transaction — including the balance change — is rolled back.
    // Concurrent repeats queue on the account row lock first, then hit the same key.
    private static final String APPLY_SQL =
            "WITH upd AS ("
            + " UPDATE accounts SET balance = balance + :delta, version = version + 1, last_updated = now()"
            + " WHERE account_id = :id %s AND (balance + :delta) >= 0"
            + " RETURNING balance, version"
            + "), ins AS ("
            + " INSERT INTO transactions (transaction_id, account_id, type, amount, balance_after, processed_at, source_cluster)"
            + " SELECT :txId, :id, :type, :amount, balance, :processedAt, :cluster FROM upd"
            + " ON CONFLICT (transaction_id) DO NOTHING"
            + " RETURNING 1"
            + ") SELECT upd.balance, upd.version, (SELECT count(*) FROM ins) FROM upd";

    @POST
    @Path("/{accountId}/apply")
    @Transactional
    @CacheInvalidate(cacheName = "balance")
    public Response applyDelta(@CacheKey @PathParam("accountId") String accountId, ApplyRequest body) throws SystemException {
        double delta = body.delta == null ? 0 : body.delta;
        Long versionParam = body.version;
        UUID txId = body.transactionId == null ? null : UUID.fromString(body.transactionId);

        // One statement either way (RETURNING saves the follow-up SELECT) — one DB round
        // trip per apply, which on cloud crosses RHSI while holding one of this pod's few
        // pooled connections (see CLAUDE.md).
        String sql = txId != null
                ? String.format(APPLY_SQL, versionParam != null ? "AND version = :version" : "")
                : "UPDATE accounts SET balance = balance + :delta, version = version + 1, last_updated = now() "
                        + "WHERE account_id = :id " + (versionParam != null ? "AND version = :version " : "")
                        + "AND (balance + :delta) >= 0 RETURNING balance, version";
        Query query = Account.getEntityManager().createNativeQuery(sql)
                .setParameter("delta", delta)
                .setParameter("id", accountId);
        if (versionParam != null) {
            query.setParameter("version", versionParam);
        }
        if (txId != null) {
            query.setParameter("txId", txId)
                    .setParameter("type", body.type)
                    .setParameter("amount", BigDecimal.valueOf(body.amount == null ? Math.abs(delta) : body.amount))
                    .setParameter("processedAt", body.processedAt == null ? Instant.now() : Instant.ofEpochMilli(body.processedAt))
                    .setParameter("cluster", body.sourceCluster);
        }
        List<?> rows = query.getResultList();

        if (rows.isEmpty()) {
            Account check = Account.findById(accountId);
            if (check == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("success", false, "reason", "account not found")).build();
            }
            // A repeat of an already-applied transaction can land here too (stale version,
            // or funds since spent) — it is still a duplicate, not a rejection.
            BigDecimal alreadyApplied = txId == null ? null : storedBalanceAfter(txId);
            if (alreadyApplied != null) {
                return duplicate(accountId, alreadyApplied, check.version);
            }
            if (versionParam != null && check.version != versionParam.longValue()) {
                return Response.ok(Map.of(
                        "accountId", accountId,
                        "newBalance", check.balance,
                        "version",   check.version,
                        "success",   false,
                        "reason",    "version conflict"
                )).build();
            }
            return Response.ok(Map.of(
                    "accountId", accountId,
                    "newBalance", check.balance,
                    "version",   check.version,
                    "success",   false,
                    "reason",    "insufficient funds"
            )).build();
        }

        Object[] row = (Object[]) rows.get(0);
        long newVersion = ((Number) row[1]).longValue();
        if (txId != null && ((Number) row[2]).longValue() == 0) {
            // The balance was changed by this statement but the transaction id already
            // exists: undo it. The version this caller should cache is the committed one.
            BigDecimal stored = storedBalanceAfter(txId);
            transactionManager.setRollbackOnly();
            return duplicate(accountId, stored, newVersion - 1);
        }
        return Response.ok(Map.of(
                "accountId", accountId,
                "newBalance", (BigDecimal) row[0],
                "version",   newVersion,
                "success",   true,
                "reason",    ""
        )).build();
    }

    private static BigDecimal storedBalanceAfter(UUID txId) {
        List<?> found = Account.getEntityManager()
                .createNativeQuery("SELECT balance_after FROM transactions WHERE transaction_id = :txId")
                .setParameter("txId", txId)
                .getResultList();
        return found.isEmpty() ? null : (BigDecimal) found.get(0);
    }

    private static Response duplicate(String accountId, BigDecimal balanceAfter, long version) {
        return Response.ok(Map.of(
                "accountId", accountId,
                "newBalance", balanceAfter,
                "version",   version,
                "success",   true,
                "duplicate", true,
                "reason",    ""
        )).build();
    }
}
