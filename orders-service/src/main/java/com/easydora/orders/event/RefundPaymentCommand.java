package com.easydora.orders.event;

/**
 * A command, not a fact-event, despite living on the same order.exchange as
 * order.created/order.status-changed (ADR-0034): Orders is instructing
 * Billing to refund a specific payment, not just broadcasting something
 * that already happened -- the same distinction ReserveStockCommand already
 * draws with inventory-service (stock.reserve is an instruction, not a
 * fact; stock.reserved/stock.insufficient are the facts that follow it).
 * Named accordingly, even though its routing key (payment.refund.requested)
 * reads like the past-tense fact-events elsewhere in this project.
 *
 * orderId is the only field: Billing already owns the authoritative
 * transactionId/amount for this order's Payment (it's the one that set
 * them), so relaying Orders' own copy of either would mean Billing trusting
 * a foreign echo of data it's already the source of truth for, for no
 * benefit -- the same reasoning that also keeps PaymentMockService.refund
 * from inventing a new refund-specific identifier (see ADR-0034).
 */
public class RefundPaymentCommand {
    private String orderId;

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }
}
