package com.easydora.billing.service.provider;

public class PaymentResult {
    private final boolean approved;
    private final String transactionId;
    private final String failureReason;
    
    private PaymentResult(boolean approved, String transactionId, String failureReason) {
        this.approved = approved;
        this.transactionId = transactionId;
        this.failureReason = failureReason;
    }
    
    public static PaymentResult approved(String transactionId) {
        return new PaymentResult(true, transactionId, null);
    }
    
    public static PaymentResult failed(String failureReason) {
        return new PaymentResult(false, null, failureReason);
    }
    
    // Getters
    public boolean isApproved() { return approved; }
    public String getTransactionId() { return transactionId; }
    public String getFailureReason() { return failureReason; }
}