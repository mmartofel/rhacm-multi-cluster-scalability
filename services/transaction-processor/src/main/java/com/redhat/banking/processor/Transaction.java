package com.redhat.banking.processor;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "transactions")
public class Transaction extends PanacheEntityBase {

    @Id
    @Column(name = "transaction_id", nullable = false)
    public String transactionId;

    @Column(name = "account_id", nullable = false)
    public String accountId;

    @Column(name = "type", nullable = false, length = 6)
    public String type;

    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    public BigDecimal amount;

    @Column(name = "balance_after", precision = 15, scale = 2)
    public BigDecimal balanceAfter;

    @Column(name = "processed_at")
    public Instant processedAt = Instant.now();

    @Column(name = "source_cluster", nullable = false, length = 10)
    public String sourceCluster;
}
