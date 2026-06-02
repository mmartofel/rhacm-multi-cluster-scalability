package com.redhat.banking.dashboard;

import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.WebSocket;

@WebSocket(path = "/ws/metrics")
public class DashboardSocket {

    @OnOpen
    public void onOpen() {
        // connection tracked automatically by OpenConnections
    }
}
