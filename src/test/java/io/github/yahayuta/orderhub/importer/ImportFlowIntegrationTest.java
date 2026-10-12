package io.github.yahayuta.orderhub.importer;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.yahayuta.orderhub.mock.MockMalls;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The whole import flow against a real PostgreSQL (CI starts one as a service container; locally,
 * {@code docker compose up -d db}). The marketplaces are the in-memory mocks behind a JDK HTTP
 * server, so the real HTTP clients, retries and SQL are all exercised.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfEnvironmentVariable(named = "SPRING_DATASOURCE_URL", matches = ".+")
class ImportFlowIntegrationTest {

    static final MockMalls MALLS = new MockMalls(new ObjectMapper());
    static final HttpServer SERVER = startMockServer();

    @DynamicPropertySource
    static void malls(DynamicPropertyRegistry registry) {
        String base = "http://localhost:" + SERVER.getAddress().getPort();
        registry.add("orderhub.malls.sakura.base-url", () -> base + "/sakura");
        registry.add("orderhub.malls.komorebi.base-url", () -> base + "/komorebi");
        registry.add("orderhub.importing.initial-backoff", () -> "PT0.01S");
    }

    @Autowired
    ImportService importer;
    @Autowired
    OrderRepository orders;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    TestRestTemplate http;

    @AfterAll
    static void stop() {
        SERVER.stop(0);
    }

    @Test
    @Order(1)
    void firstImportStoresEveryOrder() {
        jdbc.sql("TRUNCATE orders, order_lines, import_cursors, import_runs RESTART IDENTITY CASCADE").update();

        ImportRun run = importer.importMall("sakura");

        assertThat(run.status()).isEqualTo(ImportRun.Status.SUCCEEDED);
        assertThat(run.fetched()).isEqualTo(3);
        assertThat(run.inserted()).isEqualTo(3);
        assertThat(run.flagged()).isZero();
        assertThat(orders.cursor("sakura")).contains(Instant.parse("2026-10-01T06:30:00Z"));
    }

    @Test
    @Order(2)
    void importingAgainChangesNothing() {
        ImportRun run = importer.importMall("sakura");

        // the overlap window re-reads the latest order on purpose; it must not be stored twice
        assertThat(run.fetched()).isPositive();
        assertThat(run.inserted()).isZero();
        assertThat(run.updated()).isZero();
        assertThat(run.unchanged()).isEqualTo(run.fetched());
        assertThat(count("SELECT count(*) FROM orders WHERE mall = 'sakura'")).isEqualTo(3);
    }

    @Test
    @Order(3)
    void aCancellationOnTheMarketplaceUpdatesTheStoredOrder() {
        MALLS.cancel("sakura", "SK-240002", Instant.parse("2026-10-02T01:00:00Z"));

        ImportRun run = importer.importMall("sakura");

        assertThat(run.updated()).isEqualTo(1);
        assertThat(orders.findByExternalId("sakura", "SK-240002").orElseThrow().status()).isEqualTo("CANCELLED");
        assertThat(count("SELECT count(*) FROM orders WHERE mall = 'sakura'")).isEqualTo(3);
    }

    @Test
    @Order(4)
    void messyMarketplaceDataIsNormalisedAndProblemsAreFlagged() {
        ImportRun run = importer.importMall("komorebi");

        assertThat(run.inserted()).isEqualTo(3);
        assertThat(run.flagged()).isEqualTo(2);
        long fullWidth = orders.findByExternalId("komorebi", "KM-0001").orElseThrow().id();
        assertThat(orders.lines(fullWidth)).extracting(OrderRepository.LineRow::sku).containsExactly("MUG-RED-01");
        assertThat(orders.findByExternalId("komorebi", "KM-0002").orElseThrow().issues())
                .isEqualTo("lines add up to 4,400 but the order says 5,000");
        assertThat(orders.findByExternalId("komorebi", "KM-0003").orElseThrow().issues())
                .isEqualTo("unknown state \"on_hold\"");
    }

    @Test
    @Order(5)
    void aBriefOutageIsRetried() {
        MALLS.failNextCalls("komorebi", 2);

        ImportRun run = importer.importMall("komorebi");

        assertThat(run.status()).isEqualTo(ImportRun.Status.SUCCEEDED);
        assertThat(run.attempts()).isEqualTo(3);
    }

    @Test
    @Order(6)
    void aLongOutageIsRecordedAndTheCursorStays() {
        Instant before = orders.cursor("komorebi").orElseThrow();
        MALLS.failNextCalls("komorebi", 5);

        ImportRun run = importer.importMall("komorebi");

        assertThat(run.status()).isEqualTo(ImportRun.Status.FAILED);
        assertThat(run.attempts()).isEqualTo(3);
        assertThat(run.error()).contains("503");
        assertThat(orders.cursor("komorebi")).contains(before);
        MALLS.failNextCalls("komorebi", 0);
    }

    @Test
    @Order(7)
    void theReviewQueueIsAvailableOverHttp() {
        String body = http.getForObject("/api/orders?needsReview=true", String.class);
        assertThat(body).contains("KM-0002", "KM-0003").doesNotContain("SK-2400");
        String runs = http.getForObject("/api/imports", String.class);
        assertThat(runs).contains("FAILED", "SUCCEEDED");
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private static HttpServer startMockServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/sakura/orders",
                    exchange -> respond(exchange, () -> MALLS.sakuraOrders(query(exchange).get("updated_after"))));
            server.createContext("/komorebi/orders",
                    exchange -> respond(exchange, () -> MALLS.komorebiOrders(query(exchange).get("since"))));
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private interface Body {
        String get();
    }

    private static void respond(HttpExchange exchange, Body body) throws IOException {
        int status;
        byte[] bytes;
        try {
            bytes = body.get().getBytes(StandardCharsets.UTF_8);
            status = 200;
            exchange.getResponseHeaders().add("Content-Type", "application/json");
        } catch (MockMalls.Unavailable e) {
            bytes = e.getMessage().getBytes(StandardCharsets.UTF_8);
            status = 503;
        }
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static Map<String, String> query(HttpExchange exchange) {
        Map<String, String> params = new HashMap<>();
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw != null) {
            for (String pair : raw.split("&")) {
                int eq = pair.indexOf('=');
                params.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return params;
    }
}
