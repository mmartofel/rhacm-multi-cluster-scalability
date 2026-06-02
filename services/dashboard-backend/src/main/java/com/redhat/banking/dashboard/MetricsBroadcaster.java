package com.redhat.banking.dashboard;

import io.quarkus.logging.Log;
import io.quarkus.websockets.next.OpenConnections;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class MetricsBroadcaster {

    @Inject
    OpenConnections connections;

    public void publish(String json) {
        var open = connections.findByPath("/ws/metrics");
        if (open.iterator().hasNext()) {
            open.broadcast().sendText(json)
                    .subscribe().with(ignored -> {}, err -> Log.debugf("WS send skipped: %s", err.getMessage()));
        }
    }
}
