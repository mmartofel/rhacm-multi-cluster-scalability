package com.redhat.banking.dashboard;

import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.inject.Inject;

@WebSocket(path = "/ws/metrics")
public class DashboardSocket {

    @Inject
    MetricsBroadcaster broadcaster;

    @OnOpen
    public void onOpen(WebSocketConnection connection) {
        broadcaster.register(connection);
    }

    @OnClose
    public void onClose(WebSocketConnection connection) {
        broadcaster.unregister(connection);
    }
}
