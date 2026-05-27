package com.redhat.banking.account;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "accounts")
public class Account extends PanacheEntityBase {

    @Id
    @Column(name = "account_id", nullable = false, length = 20)
    public String accountId;

    @Column(name = "balance", nullable = false, precision = 15, scale = 2)
    public BigDecimal balance;

    @Column(name = "last_updated")
    public Instant lastUpdated = Instant.now();
}
