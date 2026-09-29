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
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;

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

    @POST
    @Path("/{accountId}/apply")
    @Transactional
    @CacheInvalidate(cacheName = "balance")
    public Response applyDelta(@CacheKey @PathParam("accountId") String accountId, Map<String, Number> body) {
        double delta = body.getOrDefault("delta", 0).doubleValue();
        Number versionParam = body.get("version");

        // RETURNING hands back the new balance/version from the UPDATE itself, saving the
        // follow-up SELECT — one fewer DB round trip per apply, which on cloud crosses RHSI
        // while holding one of this pod's few pooled connections (see CLAUDE.md).
        List<?> rows;
        if (versionParam != null) {
            rows = Account.getEntityManager()
                    .createNativeQuery(
                            "UPDATE accounts SET balance = balance + :delta, version = version + 1, last_updated = now() " +
                            "WHERE account_id = :id AND version = :version AND (balance + :delta) >= 0 " +
                            "RETURNING balance, version")
                    .setParameter("delta", delta)
                    .setParameter("id", accountId)
                    .setParameter("version", versionParam.longValue())
                    .getResultList();
        } else {
            rows = Account.getEntityManager()
                    .createNativeQuery(
                            "UPDATE accounts SET balance = balance + :delta, version = version + 1, last_updated = now() " +
                            "WHERE account_id = :id AND (balance + :delta) >= 0 " +
                            "RETURNING balance, version")
                    .setParameter("delta", delta)
                    .setParameter("id", accountId)
                    .getResultList();
        }

        if (rows.isEmpty()) {
            Account check = Account.findById(accountId);
            if (check == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("success", false, "reason", "account not found")).build();
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
        return Response.ok(Map.of(
                "accountId", accountId,
                "newBalance", (BigDecimal) row[0],
                "version",   ((Number) row[1]).longValue(),
                "success",   true,
                "reason",    ""
        )).build();
    }
}
