package com.redhat.banking.account;

// Body of POST /api/accounts/{id}/apply. The transaction fields are optional: with a
// transactionId the apply is idempotent and also writes the transactions row (see
// AccountResource.applyDelta); without one it only updates the balance.
public class ApplyRequest {
    public Double delta;
    public Long version;
    public String transactionId;
    public String type;
    public Double amount;
    public Long processedAt;
    public String sourceCluster;
}
