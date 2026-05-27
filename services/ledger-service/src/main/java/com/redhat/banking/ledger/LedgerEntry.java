package com.redhat.banking.ledger;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "ledger_entries")
public class LedgerEntry extends PanacheEntity {

    @Column(name = "account_id", nullable = false, length = 20)
    public String accountId;

    @Column(name = "running_balance", nullable = false, precision = 15, scale = 2)
    public BigDecimal runningBalance;

    @Column(name = "as_of")
    public Instant asOf = Instant.now();

    @Column(name = "source_cluster", length = 10)
    public String sourceCluster;
}
