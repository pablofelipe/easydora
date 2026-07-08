package com.easydora.e2e;

import com.easydora.e2e.support.E2ETestSupport;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * CI Phase 3 (cross-service e2e, ADR-0013): drives the real order lifecycle
 * across four independently running services -- auth-service, orders-service,
 * inventory-service, billing-service, each started as its own process
 * against one shared Postgres/RabbitMQ pair -- through their public HTTP
 * APIs only. No AMQP message is hand-built anywhere in this test.
 *
 * Covers four architectural flows in one continuous run: Auth -&gt; Orders
 * (login creates/activates the real Buyer, which orders-service's own
 * authorization requires before it will place an order for that user),
 * Order Created -&gt; Billing, and both Stock Reserved -&gt; Orders /
 * Stock Insufficient -&gt; Orders (one order per outcome).
 *
 * Product creation is out of scope here (that producer is covered by
 * CatalogOnboardingE2ETest) -- the two inventory rows this test needs are
 * seeded directly in Postgres, the same way existing Phase 2 wiring tests
 * seed prerequisite state that isn't the flow under test.
 */
class OrderLifecycleE2ETest extends E2ETestSupport {

    private static final String AUTH_URL = envOrDefault("AUTH_BASE_URL", "http://localhost:8081");
    private static final String ORDERS_URL = envOrDefault("ORDERS_BASE_URL", "http://localhost:8084");
    private static final String BILLING_URL = envOrDefault("BILLING_BASE_URL", "http://localhost:8085");

    @Test
    void orderLifecycleFlowsThroughAuthInventoryAndBilling() throws Exception {
        String suffix = UUID.randomUUID().toString();
        String email = "buyer-" + suffix + "@example.com";
        // Generated per run from the same suffix as the email -- not a
        // credential worth protecting (it only ever authenticates this
        // run's throwaway user, in an ephemeral Postgres/RabbitMQ pair torn
        // down at the end of the job), and a fixed literal here reads to
        // secret scanners as a real password even though it isn't one.
        String password = "Pwd-" + suffix;

        // --- signup + verify + login: real UserRegisteredEvent then real
        // JwtCreatedEvent, both consumed by orders-service's own
        // UserEventsConsumer, which is what actually creates/activates the
        // Buyer record OrderService.createOrder requires (Auth -> Orders) ---
        Map<String, Object> signupBody = Map.of(
                "email", email,
                "password", password,
                "firstName", "Riley",
                "lastName", "Buyer",
                "role", "BUYER");
        HttpResponse<String> signupResponse = postJson(AUTH_URL, "/signup", signupBody, Map.of());
        assertEquals(201, signupResponse.statusCode(), "signup should succeed: " + signupResponse.body());
        Map<String, Object> signup = parse(signupResponse.body());
        String buyerId = String.valueOf(((Number) signup.get("id")).longValue());
        String verificationToken = (String) signup.get("verificationToken");
        assertNotNull(verificationToken, "signup response should include a verification token");

        HttpResponse<String> verifyResponse = getJson(AUTH_URL,
                "/verify-email?token=" + verificationToken, Map.of());
        assertEquals(200, verifyResponse.statusCode(), "email verification should succeed: " + verifyResponse.body());

        Map<String, Object> loginBody = Map.of("email", email, "password", password);
        HttpResponse<String> loginResponse = postJson(AUTH_URL, "/login", loginBody, Map.of());
        assertEquals(200, loginResponse.statusCode(), "login should succeed: " + loginResponse.body());
        String token = (String) parse(loginResponse.body()).get("token");
        assertNotNull(token, "login response should include a JWT");

        // --- prerequisite seeding, not the flow under test: two inventory
        // rows, one with enough stock and one without ---
        String sufficientProductId = "e2e-order-sufficient-" + suffix;
        String insufficientProductId = "e2e-order-insufficient-" + suffix;
        seedInventory(sufficientProductId, 10);
        seedInventory(insufficientProductId, 1);

        Map<String, String> buyerHeaders = new HashMap<>(bearer(token));
        buyerHeaders.put("X-User-Id", buyerId);

        // --- Order 1: enough stock -> real ReserveStockCommand -> real
        // stock.reserved -> orders-service moves it to INVENTORY_RESERVED
        // (Stock Reserved -> Orders). The same createOrder call also
        // publishes a real order.created (Order Created -> Billing). The
        // real JwtAuthenticationFilter/X-User-Id combination this call
        // depends on can only succeed because of the real Auth -> Orders
        // fan-out above -- there is no other way to reach a real Buyer. ---
        String reservedOrderId = createOrder(buyerHeaders, sufficientProductId, 2);
        String reservedState = awaitOrderState(buyerHeaders, reservedOrderId);
        assertEquals("INVENTORY_RESERVED", reservedState,
                "order " + reservedOrderId + " should reach INVENTORY_RESERVED via the real stock.reserved publish");

        // --- Order 2: not enough stock -> real stock.insufficient -> orders
        // moves it to INVENTORY_FAILED (Stock Insufficient -> Orders) ---
        String failedOrderId = createOrder(buyerHeaders, insufficientProductId, 5);
        String failedState = awaitOrderState(buyerHeaders, failedOrderId);
        assertEquals("INVENTORY_FAILED", failedState,
                "order " + failedOrderId + " should reach INVENTORY_FAILED via the real stock.insufficient publish");

        // --- Order Created -> Billing: a real Payment for the first order.
        // billing-service now authenticates via the same JWT broadcast cache
        // as orders-service (ADR-0015), so the token already obtained from
        // login above is reused here instead of Basic Auth credentials. ---
        HttpResponse<String> paymentResponse = awaitHttp(Duration.ofSeconds(10),
                () -> getJson(BILLING_URL, "/api/payments/order/" + reservedOrderId, bearer(token)),
                r -> r.statusCode() == 200);
        assertEquals(200, paymentResponse.statusCode(),
                "billing-service should have created a Payment for order " + reservedOrderId
                        + " via the real order.created consumer: " + paymentResponse.body());
        Map<String, Object> payment = parse(paymentResponse.body());
        assertEquals(reservedOrderId, payment.get("orderId"));
        assertEquals("PENDING", payment.get("status"));
    }

    private void seedInventory(String productId, int quantity) throws Exception {
        try (Connection conn = openDb();
             PreparedStatement st = conn.prepareStatement(
                     "INSERT INTO inventory_schema.inventory (product_id, quantity, reserved, available, deleted) "
                             + "VALUES (?, ?, 0, true, false)")) {
            st.setString(1, productId);
            st.setInt(2, quantity);
            st.executeUpdate();
        }
    }

    private String createOrder(Map<String, String> buyerHeaders, String productId, int quantity) throws Exception {
        Map<String, Object> item = Map.of(
                "productId", productId,
                "quantity", quantity,
                "unitPrice", new BigDecimal("9.99"));
        Map<String, Object> orderBody = Map.of("items", List.of(item));
        HttpResponse<String> response = postJson(ORDERS_URL, "/createOrder", orderBody, buyerHeaders);
        assertEquals(200, response.statusCode(), "order creation should succeed: " + response.body());
        return String.valueOf(parse(response.body()).get("id"));
    }

    private String awaitOrderState(Map<String, String> buyerHeaders, String orderId) throws Exception {
        HttpResponse<String> response = awaitHttp(Duration.ofSeconds(10),
                () -> getJson(ORDERS_URL, "/" + orderId, buyerHeaders),
                r -> r.statusCode() == 200 && !"PROCESSING".equals(parseQuietly(r.body()).get("state")));
        assertEquals(200, response.statusCode(), "order lookup should succeed: " + response.body());
        return (String) parse(response.body()).get("state");
    }
}
