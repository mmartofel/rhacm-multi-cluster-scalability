package com.redhat.banking.dashboard;

import io.quarkus.logging.Log;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class MetricsBroadcaster {

    private final Set<WebSocketConnection> connections = ConcurrentHashMap.newKeySet();

    public void register(WebSocketConnection conn) {
        connections.add(conn);
    }

    public void unregister(WebSocketConnection conn) {
        connections.remove(conn);
    }

    public void publish(String json) {
        connections.removeIf(WebSocketConnection::isClosed);
        for (WebSocketConnection conn : connections) {
            conn.sendText(json)
                    .subscribe().with(ignored -> {}, err -> Log.debugf("WS send skipped: %s", err.getMessage()));
        }
    }
}
