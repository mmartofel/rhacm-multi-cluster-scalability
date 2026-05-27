package com.redhat.banking.processor;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import java.util.Map;

@RegisterRestClient(configKey = "account-service")
@Path("/api/accounts")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public interface AccountServiceClient {

    @POST
    @Path("/{accountId}/apply")
    ApplyResponse applyDelta(@PathParam("accountId") String accountId, Map<String, Double> body);
}
