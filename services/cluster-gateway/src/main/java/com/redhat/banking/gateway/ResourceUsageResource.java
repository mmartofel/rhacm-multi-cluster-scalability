package com.redhat.banking.gateway;

import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceQuota;
import io.fabric8.kubernetes.api.model.metrics.v1beta1.ContainerMetrics;
import io.fabric8.kubernetes.api.model.metrics.v1beta1.PodMetrics;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.runtime.Startup;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// Backs the dashboard's "Resource Consumption" tab (issue #26): per namespace, what the
// ResourceQuota reports as used vs hard (the sum of pod requests/limits — the same numbers
// the OpenShift console charts) next to what the pods really consume right now, from the
// metrics API. RBAC for both reads is in app-services/cluster-gateway/base/rbac-resources.yaml.
@Startup
@Path("/api/gateway")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class ResourceUsageResource {

    private static final List<String> NAMESPACES = List.of("banking-demo", "banking-infra");

    // Plain POJOs with public fields — nested records may be missed by Quarkus's
    // build-time Jackson scan and serialize as {} (see KafkaPartitionStats.PartitionLag).
    public static class QuotaItem {
        public String resource;
        public double used;   // CPU in cores, memory/storage in bytes, counts as-is
        public double hard;

        public QuotaItem() {}
        public QuotaItem(String resource, double used, double hard) {
            this.resource = resource;
            this.used = used;
            this.hard = hard;
        }
    }

    public static class NamespaceResources {
        public String namespace;
        public String quota;                       // null when the namespace has no ResourceQuota
        public List<QuotaItem> items = new ArrayList<>();
        public double usageCpuCores = -1;          // -1 = metrics API gave no answer
        public double usageMemoryBytes = -1;
    }

    @Inject
    KubernetesClient k8s;

    private volatile List<NamespaceResources> summary = List.of();

    // Same reason as ScalingResource: pay the first-ever fabric8 deserialization of these
    // model classes during the probe-budgeted startup window, not on a live poll.
    @PostConstruct
    void warmUp() {
        refresh();
    }

    // Background refresh — the REST path below only reads a volatile field.
    @Scheduled(every = "PT5S")
    void refresh() {
        List<NamespaceResources> fresh = new ArrayList<>();
        for (String ns : NAMESPACES) {
            NamespaceResources r = new NamespaceResources();
            r.namespace = ns;
            readQuota(ns, r);
            readUsage(ns, r);
            fresh.add(r);
        }
        summary = fresh;
    }

    @GET
    @Path("/resources/summary")
    public Response resourcesSummary() {
        return Response.ok(summary).build();
    }

    private void readQuota(String ns, NamespaceResources r) {
        try {
            for (ResourceQuota q : k8s.resourceQuotas().inNamespace(ns).list().getItems()) {
                if (q.getStatus() == null || q.getStatus().getHard() == null) continue;
                Map<String, Quantity> hard = q.getStatus().getHard();
                Map<String, Quantity> used = q.getStatus().getUsed();
                r.quota = q.getMetadata().getName();
                for (Map.Entry<String, Quantity> e : hard.entrySet()) {
                    Quantity u = used != null ? used.get(e.getKey()) : null;
                    r.items.add(new QuotaItem(e.getKey(), amount(u), amount(e.getValue())));
                }
                break; // one quota per namespace (infra/namespaces/*-limits.yaml)
            }
        } catch (Exception e) {
            // leave items empty — the tab shows "no quota data"
        }
    }

    // Best-effort: the metrics API is briefly unavailable whenever metrics-server rolls
    // (see CLAUDE.md, Phase 0 notes) — report -1 and keep the quota numbers.
    private void readUsage(String ns, NamespaceResources r) {
        try {
            double cpu = 0;
            double mem = 0;
            for (PodMetrics pm : k8s.top().pods().metrics(ns).getItems()) {
                for (ContainerMetrics c : pm.getContainers()) {
                    Map<String, Quantity> usage = c.getUsage();
                    if (usage == null) continue;
                    cpu += amount(usage.get("cpu"));
                    mem += amount(usage.get("memory"));
                }
            }
            r.usageCpuCores = cpu;
            r.usageMemoryBytes = mem;
        } catch (Exception e) {
            // stays -1
        }
    }

    // Quantity -> base unit: "500m" -> 0.5, "12Gi" -> 12884901888, "45" -> 45.
    private static double amount(Quantity q) {
        if (q == null) return 0;
        try {
            return Quantity.getAmountInBytes(q).doubleValue();
        } catch (Exception e) {
            return 0;
        }
    }
}
