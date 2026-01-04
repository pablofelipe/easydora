package com.easydora.billing.service;

public class PaymentResult {
    private boolean success;
    private String transactionId;
    private String errorMessage;
    
    public PaymentResult() {}
    
    public PaymentResult(boolean success, String transactionId, String errorMessage) {
        this.success = success;
        this.transactionId = transactionId;
        this.errorMessage = errorMessage;
    }
    
    // Getters e Setters
    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
    
    public String getTransactionId() { return transactionId; }
    public void setTransactionId(String transactionId) { this.transactionId = transactionId; }
    
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    
    @Override
    public String toString() {
        return "PaymentResult{" +
                "success=" + success +
                ", transactionId='" + transactionId + '\'' +
                ", errorMessage='" + errorMessage + '\'' +
                '}';
    }
}