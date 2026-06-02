package com.redhat.banking.account;

import io.quarkus.cache.CacheInvalidate;
import io.quarkus.cache.CacheKey;
import io.quarkus.cache.CacheResult;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.math.BigDecimal;
import java.util.Map;

@Path("/api/accounts")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class AccountResource {

    @GET
    @Path("/{accountId}/balance")
    @CacheResult(cacheName = "balance")
    public Response getBalance(@PathParam("accountId") String accountId) {
        Account account = Account.findById(accountId);
        if (account == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(Map.of(
                "accountId", accountId,
                "balance", account.balance,
                "version", account.version
        )).build();
    }

    @POST
    @Path("/{accountId}/apply")
    @Transactional
    @CacheInvalidate(cacheName = "balance")
    public Response applyDelta(@CacheKey @PathParam("accountId") String accountId, Map<String, Number> body) {
        double delta = body.getOrDefault("delta", 0).doubleValue();
        Number versionParam = body.get("version");

        int updated;
        if (versionParam != null) {
            updated = Account.getEntityManager()
                    .createNativeQuery(
                            "UPDATE accounts SET balance = balance + :delta, version = version + 1, last_updated = now() " +
                            "WHERE account_id = :id AND version = :version AND (balance + :delta) >= 0")
                    .setParameter("delta", delta)
                    .setParameter("id", accountId)
                    .setParameter("version", versionParam.longValue())
                    .executeUpdate();
        } else {
            updated = Account.getEntityManager()
                    .createNativeQuery(
                            "UPDATE accounts SET balance = balance + :delta, version = version + 1, last_updated = now() " +
                            "WHERE account_id = :id AND (balance + :delta) >= 0")
                    .setParameter("delta", delta)
                    .setParameter("id", accountId)
                    .executeUpdate();
        }

        if (updated == 0) {
            Account check = Account.findById(accountId);
            if (check == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("success", false, "reason", "account not found")).build();
            }
            // If a version was supplied and it no longer matches, it's a conflict
            // (another writer incremented the version between our read and this write)
            if (versionParam != null && check.version != versionParam.longValue()) {
                return Response.ok(Map.of(
                        "accountId", accountId,
                        "newBalance", check.balance,
                        "version", check.version,
                        "success", false,
                        "reason", "version conflict"
                )).build();
            }
            return Response.ok(Map.of(
                    "accountId", accountId,
                    "newBalance", check.balance,
                    "version", check.version,
                    "success", false,
                    "reason", "insufficient funds"
            )).build();
        }

        Account refreshed = Account.findById(accountId);
        return Response.ok(Map.of(
                "accountId", accountId,
                "newBalance", refreshed.balance,
                "version", refreshed.version,
                "success", true,
                "reason", ""
        )).build();
    }
}
