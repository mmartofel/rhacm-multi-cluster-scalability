package com.redhat.banking.generator;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

@Path("/api/generator")
@Produces(MediaType.APPLICATION_JSON)
public class TpsConfigResource {

    @Inject
    TransactionGeneratorService generatorService;

    @GET
    @Path("/status")
    public Response status() {
        return Response.ok(Map.of(
                "tpsRate", generatorService.getTpsRate(),
                "totalGenerated", generatorService.getGenerated()
        )).build();
    }

    @PUT
    @Path("/tps/{rate}")
    public Response setTps(@PathParam("rate") int rate) {
        generatorService.setTpsRate(rate);
        return Response.ok(Map.of("tpsRate", generatorService.getTpsRate())).build();
    }

    @PUT
    @Path("/partitions")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response setPartitions(Map<String, List<Integer>> body) {
        List<Integer> list = body.getOrDefault("partitions", List.of());
        int[] arr = list.stream().mapToInt(Integer::intValue).toArray();
        generatorService.setOwnedPartitions(arr);
        return Response.ok(Map.of("ownedPartitions", list)).build();
    }
}
