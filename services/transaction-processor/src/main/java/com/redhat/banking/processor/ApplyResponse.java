package com.redhat.banking.processor;

public class ApplyResponse {
    public String accountId;
    public double newBalance;
    public long version;
    public boolean success;
    // true when this transaction id had already been applied (redelivery) — nothing changed
    public boolean duplicate;
    public String reason;
}
