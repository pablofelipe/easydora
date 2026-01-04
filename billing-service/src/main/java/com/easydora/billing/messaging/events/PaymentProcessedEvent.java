package com.easydora.billing.messaging.events;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class PaymentProcessedEvent {
    private String paymentId;
    private Long orderId;
    private BigDecimal amount;
    private PaymentStatus status;
    private String transactionId;
    private String failureReason;
    private LocalDateTime processedAt;
    
    public enum PaymentStatus {
        APPROVED, FAILED, PENDING, REFUNDED
    }
    
    public PaymentProcessedEvent() {}
    
    // Getters e Setters
    public String getPaymentId() { return paymentId; }
    public void setPaymentId(String paymentId) { this.paymentId = paymentId; }
    
    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    
    public PaymentStatus getStatus() { return status; }
    public void setStatus(PaymentStatus status) { this.status = status; }
    
    public String getTransactionId() { return transactionId; }
    public void setTransactionId(String transactionId) { this.transactionId = transactionId; }
    
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
    
    public LocalDateTime getProcessedAt() { return processedAt; }
    public void setProcessedAt(LocalDateTime processedAt) { this.processedAt = processedAt; }
    
    @Override
    public String toString() {
        return "PaymentProcessedEvent{" +
                "paymentId='" + paymentId + '\'' +
                ", orderId='" + orderId + '\'' +
                ", amount=" + amount +
                ", status=" + status +
                ", transactionId='" + transactionId + '\'' +
                ", failureReason='" + failureReason + '\'' +
                ", processedAt=" + processedAt +
                '}';
    }
}