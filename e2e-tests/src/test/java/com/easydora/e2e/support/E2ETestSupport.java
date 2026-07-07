package com.easydora.e2e.support;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Shared plumbing for CI Phase 3 cross-service e2e tests (ADR-0013): a
 * plain HTTP client for calling real services' public APIs, JSON parsing,
 * a raw JDBC escape hatch for the rare checkpoint no public API can observe
 * yet, and the bounded-poll idiom already used throughout Phase 2's wiring
 * tests -- never a fixed sleep, always a real condition with a timeout.
 */
public abstract class E2ETestSupport {

    protected static final String DB_HOST = envOrDefault("DB_HOST", "localhost");
    protected static final String DB_PORT = envOrDefault("DB_PORT", "5432");
    protected static final String DB_NAME = envOrDefault("DB_NAME", "easydora");
    protected static final String DB_USER = envOrDefault("DB_USER", "admin");
    protected static final String DB_PASSWORD = envOrDefault("DB_PASSWORD", "local_dev_placeholder");

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    protected Map<String, String> bearer(String token) {
        return Map.of("Authorization", "Bearer " + token);
    }

    protected Map<String, String> basicAuth(String username, String password) {
        String encoded = Base64.getEncoder().encodeToString((username + ":" + password).getBytes());
        return Map.of("Authorization", "Basic " + encoded);
    }

    protected HttpResponse<String> postJson(String baseUrl, String path, Object body, Map<String, String> headers)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        headers.forEach(builder::header);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    protected HttpResponse<String> getJson(String baseUrl, String path, Map<String, String> headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .GET();
        headers.forEach(builder::header);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @SuppressWarnings("unchecked")
    protected Map<String, Object> parse(String json) throws Exception {
        return mapper.readValue(json, Map.class);
    }

    protected Map<String, Object> parseQuietly(String json) {
        try {
            return parse(json);
        } catch (Exception e) {
            return Map.of();
        }
    }

    protected Connection openDb() throws Exception {
        String url = "jdbc:postgresql://" + DB_HOST + ":" + DB_PORT + "/" + DB_NAME;
        return DriverManager.getConnection(url, DB_USER, DB_PASSWORD);
    }

    protected boolean awaitCondition(Duration timeout, Supplier<Boolean> condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        boolean result;
        do {
            result = condition.get();
            if (result) {
                return true;
            }
            Thread.sleep(250);
        } while (System.currentTimeMillis() < deadline);
        return result;
    }

    protected HttpResponse<String> awaitHttp(Duration timeout, ThrowingSupplier<HttpResponse<String>> call,
            Predicate<HttpResponse<String>> ready) throws Exception {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        HttpResponse<String> last = call.get();
        while (!ready.test(last) && System.currentTimeMillis() < deadline) {
            Thread.sleep(250);
            last = call.get();
        }
        return last;
    }

    @FunctionalInterface
    protected interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    protected static String envOrDefault(String key, String fallback) {
        String value = System.getenv(key);
        return (value == null || value.isBlank()) ? fallback : value;
    }
}
