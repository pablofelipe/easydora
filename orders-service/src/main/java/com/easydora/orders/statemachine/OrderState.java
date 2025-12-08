package com.easydora.orders.statemachine;

public enum OrderState {
    PENDING,           // Pedido criado, aguardando pagamento
    PAYMENT_APPROVED,  // Pagamento aprovado
    PAYMENT_FAILED,    // Pagamento falhou
    PROCESSING,        // Em processamento
    INVENTORY_RESERVED,// Estoque reservado
    INVENTORY_FAILED,  // Falha no estoque
    SHIPPED,           // Enviado
    DELIVERED,         // Entregue
    CANCELLED,         // Cancelado
    REFUNDING          // Reembolso
}