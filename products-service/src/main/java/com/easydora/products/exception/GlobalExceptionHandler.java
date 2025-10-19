package com.easydora.products.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
    
    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleUserNotFound(UserNotFoundException ex) {
        logger.warn("User not found: {}", ex.getMessage());
        
        Map<String, Object> error = createErrorResponse(
            HttpStatus.NOT_FOUND, 
            "User Not Found", 
            ex.getMessage()
        );
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    @ExceptionHandler(InvalidUserRoleException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidUserRole(InvalidUserRoleException ex) {
        logger.warn("Invalid user role: {}", ex.getMessage());
        
        Map<String, Object> error = createErrorResponse(
            HttpStatus.FORBIDDEN,
            "Forbidden",
            ex.getMessage()
        );
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error);
    }

    @ExceptionHandler(SellerNotActiveException.class)
    public ResponseEntity<Map<String, Object>> handleSellerNotActive(SellerNotActiveException ex) {
        logger.warn("Seller not active: {}", ex.getMessage());
        
        Map<String, Object> error = createErrorResponse(
            HttpStatus.FORBIDDEN,
            "Forbidden", 
            ex.getMessage()
        );
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error);
    }

    @ExceptionHandler(ProductNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleProductNotFound(ProductNotFoundException ex) {
        logger.warn("Product not found: {}", ex.getMessage());
        
        Map<String, Object> error = createErrorResponse(
            HttpStatus.NOT_FOUND,
            "Product Not Found",
            ex.getMessage()
        );
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<Map<String, Object>> handleUnauthorized(UnauthorizedException ex) {
        logger.warn("Unauthorized access: {}", ex.getMessage());
        
        Map<String, Object> error = createErrorResponse(
            HttpStatus.FORBIDDEN,
            "Forbidden",
            ex.getMessage()
        );
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error);
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> handleRuntimeException(RuntimeException ex) {
        logger.error("Internal server error: {}", ex.getMessage(), ex);
        
        Map<String, Object> error = createErrorResponse(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "Internal Server Error",
            "An unexpected error occurred"
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }

    private Map<String, Object> createErrorResponse(HttpStatus status, String error, String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("timestamp", Instant.now());
        response.put("status", status.value());
        response.put("error", error);
        response.put("message", message);
        response.put("path", getCurrentRequestPath());
        return response;
    }

    private String getCurrentRequestPath() {
        try {
            HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes())
                    .getRequest();
            return request.getRequestURI();
        } catch (IllegalStateException e) {
            return "unknown";
        }
    }
}