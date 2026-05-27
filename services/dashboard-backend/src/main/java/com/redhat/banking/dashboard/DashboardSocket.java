package com.redhat.banking.dashboard;

import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.WebSocket;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;

@WebSocket(path = "/ws/metrics")
public class DashboardSocket {

    @Inject
    MetricsBroadcaster broadcaster;

    @OnOpen
    public Multi<String> onOpen() {
        return broadcaster.stream();
    }
}
