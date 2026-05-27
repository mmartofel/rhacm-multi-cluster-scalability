package com.redhat.banking.account;

import io.quarkus.cache.CacheResult;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.math.BigDecimal;
import java.time.Instant;
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
                "balance", account.balance
        )).build();
    }

    @POST
    @Path("/{accountId}/apply")
    @Transactional
    public Response applyDelta(@PathParam("accountId") String accountId, Map<String, Double> body) {
        double delta = body.getOrDefault("delta", 0.0);

        // Atomic balance update with overflow/underflow guard
        int updated = Account.getEntityManager()
                .createNativeQuery(
                        "UPDATE accounts SET balance = balance + :delta, last_updated = now() " +
                        "WHERE account_id = :id AND (balance + :delta) >= 0")
                .setParameter("delta", delta)
                .setParameter("id", accountId)
                .executeUpdate();

        if (updated == 0) {
            Account check = Account.findById(accountId);
            if (check == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("success", false, "reason", "account not found")).build();
            }
            return Response.ok(Map.of(
                    "accountId", accountId,
                    "newBalance", check.balance,
                    "success", false,
                    "reason", "insufficient funds"
            )).build();
        }

        Account refreshed = Account.findById(accountId);
        return Response.ok(Map.of(
                "accountId", accountId,
                "newBalance", refreshed.balance,
                "success", true,
                "reason", ""
        )).build();
    }
}
