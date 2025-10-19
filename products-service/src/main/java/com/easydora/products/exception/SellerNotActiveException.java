package com.easydora.products.exception;

public class SellerNotActiveException extends RuntimeException {
    public SellerNotActiveException(String message) {
        super(message);
    }
}