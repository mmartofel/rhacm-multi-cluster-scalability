package com.redhat.banking.processor;

public class ApplyResponse {
    public String accountId;
    public double newBalance;
    public long version;
    public boolean success;
    public String reason;
}
