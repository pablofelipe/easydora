package com.easydora.e2e;

import com.easydora.e2e.support.E2ETestSupport;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CI Phase 3 (cross-service e2e, ADR-0013): drives the real seller-onboarding
 * flow across three independently running services -- auth-service,
 * products-service, inventory-service, each started as its own process
 * against one shared Postgres/RabbitMQ pair -- through their public HTTP
 * APIs only. No AMQP message is hand-built anywhere in this test; every
 * event actually on the wire (UserRegisteredEvent, JwtCreatedEvent,
 * ProductCreatedEvent) is produced by the real production code path of the
 * service that owns it.
 *
 * Covers three architectural flows in one continuous run:
 * Auth -&gt; Products (signup), JWT Created -&gt; Products (login),
 * Products -&gt; Inventory (product creation).
 */
class CatalogOnboardingE2ETest extends E2ETestSupport {

    private static final String AUTH_URL = envOrDefault("AUTH_BASE_URL", "http://localhost:8081");
    private static final String PRODUCTS_URL = envOrDefault("PRODUCTS_BASE_URL", "http://localhost:8082");
    private static final String INVENTORY_URL = envOrDefault("INVENTORY_BASE_URL", "http://localhost:8083");

    @Test
    void sellerOnboardingFlowsThroughAuthProductsAndInventory() throws Exception {
        String suffix = UUID.randomUUID().toString();
        String email = "seller-" + suffix + "@example.com";
        // Generated per run from the same suffix as the email -- not a
        // credential worth protecting (it only ever authenticates this
        // run's throwaway user, in an ephemeral Postgres/RabbitMQ pair torn
        // down at the end of the job), and a fixed literal here reads to
        // secret scanners as a real password even though it isn't one.
        String password = "Pwd-" + suffix;

        // --- Auth -> Products: signup publishes a real UserRegisteredEvent ---
        Map<String, Object> signupBody = Map.of(
                "email", email,
                "password", password,
                "firstName", "Casey",
                "lastName", "Seller",
                "role", "SELLER");
        HttpResponse<String> signupResponse = postJson(AUTH_URL, "/auth/signup", signupBody, Map.of());
        assertEquals(201, signupResponse.statusCode(), "signup should succeed: " + signupResponse.body());
        Map<String, Object> signup = parse(signupResponse.body());
        String sellerId = String.valueOf(((Number) signup.get("id")).longValue());
        String verificationToken = (String) signup.get("verificationToken");
        assertNotNull(verificationToken, "signup response should include a verification token");

        // products-service has no cached token yet at this point -- no login
        // has happened, so no jwt.created has ever been consumed -- so its
        // own HTTP API can't be called to observe this checkpoint. This is
        // the one place this test reads Postgres directly instead of through
        // a public API, specifically to isolate user.registered's effect
        // from jwt.created's effect: UserEventConsumer.handleJwtCreated also
        // creates a Seller row if none exists yet, so asserting seller
        // existence only after login would not prove user.registered itself
        // ran.
        boolean sellerCreatedInactive = awaitCondition(Duration.ofSeconds(10), () -> {
            try (Connection conn = openDb();
                 PreparedStatement st = conn.prepareStatement(
                         "SELECT active FROM products_schema.sellers WHERE user_id = ?")) {
                st.setString(1, sellerId);
                try (ResultSet rs = st.executeQuery()) {
                    return rs.next() && !rs.getBoolean("active");
                }
            } catch (Exception e) {
                return false;
            }
        });
        assertTrue(sellerCreatedInactive,
                "expected a real UserRegisteredEvent to create an inactive Seller for " + sellerId);

        // --- verify email so login (below) is allowed to succeed ---
        HttpResponse<String> verifyResponse = getJson(AUTH_URL,
                "/auth/verify-email?token=" + verificationToken, Map.of());
        assertEquals(200, verifyResponse.statusCode(), "email verification should succeed: " + verifyResponse.body());

        // --- JWT Created -> Products: login publishes a real JwtCreatedEvent ---
        Map<String, Object> loginBody = Map.of("email", email, "password", password);
        HttpResponse<String> loginResponse = postJson(AUTH_URL, "/auth/login", loginBody, Map.of());
        assertEquals(200, loginResponse.statusCode(), "login should succeed: " + loginResponse.body());
        String token = (String) parse(loginResponse.body()).get("token");
        assertNotNull(token, "login response should include a JWT");

        // The only way this call can succeed at all is if products-service's
        // real JwtConsumer/UserEventConsumer actually consumed the
        // JwtCreatedEvent this login just published and cached this exact
        // token -- there is no other path into that in-memory map. Within
        // that same listener invocation, the token cache write (in-memory,
        // instant) happens a statement or two before the Seller activation
        // (a DB write) commits, so waiting only on the HTTP status can
        // observe a real, brief in-between state -- authenticated, but not
        // yet active. Poll on the actual field, not just the status code.
        HttpResponse<String> sellerResponse = awaitHttp(Duration.ofSeconds(10),
                () -> getJson(PRODUCTS_URL, "/products/sellers/" + sellerId, bearer(token)),
                r -> r.statusCode() == 200 && Boolean.TRUE.equals(parseQuietly(r.body()).get("active")));
        assertEquals(200, sellerResponse.statusCode(),
                "products-service should authenticate the real JWT broadcast for seller " + sellerId
                        + ": " + sellerResponse.body());
        Map<String, Object> seller = parse(sellerResponse.body());
        assertEquals(email, seller.get("email"));
        assertEquals(Boolean.TRUE, seller.get("active"));

        // --- Products -> Inventory: creating a product publishes a real ProductCreatedEvent ---
        Map<String, Object> productBody = Map.of(
                "name", "E2E Widget " + suffix,
                "description", "created by CatalogOnboardingE2ETest",
                "price", 19.99,
                "initialStock", 42);
        HttpResponse<String> productResponse = postJson(PRODUCTS_URL, "/products/createProduct", productBody, bearer(token));
        assertEquals(200, productResponse.statusCode(), "product creation should succeed: " + productResponse.body());
        String productId = String.valueOf(parse(productResponse.body()).get("id"));

        HttpResponse<String> inventoryResponse = awaitHttp(Duration.ofSeconds(10),
                () -> getJson(INVENTORY_URL, "/inventory/" + productId, Map.of()),
                r -> r.statusCode() == 200);
        assertEquals(200, inventoryResponse.statusCode(),
                "inventory-service should have created a record for product " + productId
                        + " via the real product.created consumer: " + inventoryResponse.body());
        Map<String, Object> inventory = parse(inventoryResponse.body());
        assertEquals(42, ((Number) inventory.get("quantity")).intValue());
    }
}
