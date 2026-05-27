package com.redhat.banking.dashboard;

import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class MetricsBroadcaster {

    private final BroadcastProcessor<String> processor = BroadcastProcessor.create();

    public void publish(String json) {
        processor.onNext(json);
    }

    public Multi<String> stream() {
        return processor;
    }
}
