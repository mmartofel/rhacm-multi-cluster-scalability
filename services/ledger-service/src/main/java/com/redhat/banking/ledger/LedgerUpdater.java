package com.redhat.banking.ledger;

import com.redhat.banking.TransactionCommitted;
import io.quarkus.logging.Log;
import io.smallrye.reactive.messaging.annotations.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.reactive.messaging.Incoming;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

@ApplicationScoped
public class LedgerUpdater {

    private final AtomicLong processedCount = new AtomicLong(0);

    @Incoming("committed-in")
    @Blocking
    @Transactional
    public void onCommitted(TransactionCommitted event) {
        LedgerEntry entry = new LedgerEntry();
        entry.accountId = event.getAccountId();
        entry.runningBalance = BigDecimal.valueOf(event.getBalanceAfter());
        entry.asOf = event.getProcessedAt();
        entry.sourceCluster = event.getSourceCluster();
        entry.persist();
        processedCount.incrementAndGet();
        Log.debugf("Ledger updated: account=%s balance=%.2f cluster=%s",
                event.getAccountId(), event.getBalanceAfter(), event.getSourceCluster());
    }

    public long getProcessedCount() {
        return processedCount.get();
    }
}
