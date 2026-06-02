package com.redhat.banking.generator;

import com.redhat.banking.TransactionEvent;
import com.redhat.banking.TransactionType;
import io.quarkus.scheduler.Scheduled;
import java.util.concurrent.TimeUnit;
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.eclipse.microprofile.reactive.messaging.Metadata;

import java.time.Instant;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@ApplicationScoped
public class TransactionGeneratorService {

    @Inject
    @Channel("transactions-out")
    Emitter<TransactionEvent> emitter;

    @ConfigProperty(name = "TPS_RATE", defaultValue = "0")
    volatile int tpsRate;

    private final int[] ownedPartitions = parseOwnedPartitions(
            System.getenv().getOrDefault("OWNED_PARTITIONS", "0,1,2,3,4,5"));

    private static int[] parseOwnedPartitions(String spec) {
        String[] parts = spec.split(",");
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) result[i] = Integer.parseInt(parts[i].trim());
        return result;
    }

    private int computePartition(String key) {
        return ownedPartitions[Math.abs(key.hashCode()) % ownedPartitions.length];
    }

    private final Random random = new Random();
    private final AtomicLong generated = new AtomicLong(0);

    // 100 test accounts matching the seeded DB rows
    private static final String[] ACCOUNTS = buildAccounts(100);

    private static String[] buildAccounts(int count) {
        String[] a = new String[count];
        for (int i = 0; i < count; i++) {
            a[i] = String.format("ACC%05d", i + 1);
        }
        return a;
    }

    @Scheduled(every = "1s", delay = 5, delayUnit = TimeUnit.SECONDS)
    void generateBatch() {
        int rate = tpsRate;
        for (int i = 0; i < rate; i++) {
            String accountId = ACCOUNTS[random.nextInt(ACCOUNTS.length)];
            TransactionType type = random.nextBoolean() ? TransactionType.DEBIT : TransactionType.CREDIT;
            double amount = Math.round((10 + random.nextDouble() * 990) * 100.0) / 100.0;

            TransactionEvent event = TransactionEvent.newBuilder()
                    .setTransactionId(UUID.randomUUID().toString())
                    .setAccountId(accountId)
                    .setType(type)
                    .setAmount(amount)
                    .setTimestamp(Instant.now())
                    .build();

            emitter.send(Message.of(event, Metadata.of(
                    OutgoingKafkaRecordMetadata.<String>builder()
                            .withKey(accountId)
                            .withPartition(computePartition(accountId))
                            .build())));
            generated.incrementAndGet();
        }
    }

    public long getGenerated() {
        return generated.get();
    }

    public int getTpsRate() {
        return tpsRate;
    }

    public void setTpsRate(int rate) {
        this.tpsRate = Math.max(0, Math.min(rate, 10000));
    }
}
