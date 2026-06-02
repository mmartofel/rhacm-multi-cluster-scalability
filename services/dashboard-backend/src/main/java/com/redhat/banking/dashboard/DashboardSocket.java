package com.redhat.banking.dashboard;

import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.inject.Inject;

@WebSocket(path = "/ws/metrics")
public class DashboardSocket {

    @Inject
    WebSocketConnection connection;

    @Inject
    MetricsBroadcaster broadcaster;

    @OnOpen
    public void onOpen() {
        broadcaster.register(connection);
    }

    @OnClose
    public void onClose() {
        broadcaster.unregister(connection);
    }
}
