package com.redhat.banking.processor;

// Body of account-service's POST /api/accounts/{id}/apply. The transaction fields make
// the apply idempotent on transactionId and let account-service write the transactions
// row in the same statement as the balance update.
public class ApplyRequest {
    public double delta;
    public Long version;
    public String transactionId;
    public String type;
    public double amount;
    public long processedAt;
    public String sourceCluster;
}
