package io.github.yahayuta.orderhub.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.yahayuta.orderhub.domain.Normalize;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * In-memory stand-ins for the two fictional marketplaces, loaded from {@code mock/*.json}.
 * Used by {@link MockMallController} for local runs and directly by the integration test.
 * They can cancel an order (to show updates) and fail the next calls (to show retries).
 */
public class MockMalls {

    /** Thrown instead of answering, to simulate a 503 from the marketplace. */
    public static class Unavailable extends RuntimeException {
        Unavailable(String mall) {
            super(mall + " is temporarily unavailable");
        }
    }

    private final ObjectMapper json;
    private final ArrayNode sakura;
    private final ArrayNode komorebi;
    private final Map<String, Integer> failuresToSimulate = new HashMap<>();

    public MockMalls(ObjectMapper json) {
        this.json = json;
        this.sakura = load("mock/sakura-orders.json");
        this.komorebi = load("mock/komorebi-orders.json");
    }

    /** Sakura Mall: {@code GET /orders?updated_after=<ISO-8601>}. */
    public synchronized String sakuraOrders(String updatedAfter) {
        failIfAsked("sakura");
        Instant after = OffsetDateTime.parse(updatedAfter).toInstant();
        ArrayNode result = filter(sakura, o -> OffsetDateTime.parse(o.path("updated_at").asText()).toInstant(), after);
        ObjectNode body = json.createObjectNode();
        body.set("orders", result);
        return body.toString();
    }

    /** Komorebi Market: {@code GET /orders?since=yyyy/MM/dd HH:mm:ss} (Japan time). */
    public synchronized String komorebiOrders(String since) {
        failIfAsked("komorebi");
        Instant after = Normalize.slashedJst(since);
        ArrayNode result = filter(komorebi, o -> Normalize.slashedJst(o.path("modified").asText()), after);
        ObjectNode body = json.createObjectNode();
        body.set("data", result);
        body.put("count", result.size());
        return body.toString();
    }

    /** Cancels an order on the marketplace side, the way a customer cancellation would. */
    public synchronized void cancel(String mall, String id, Instant at) {
        if (mall.equals("sakura")) {
            ObjectNode order = find(sakura, "order_no", id);
            order.put("status", "CANCELLED");
            order.put("updated_at", at.atZone(Normalize.JST).toOffsetDateTime().toString());
        } else if (mall.equals("komorebi")) {
            ObjectNode order = find(komorebi, "id", id);
            order.put("state", "canceled");
            order.put("modified", Normalize.toSlashedJst(at));
        } else {
            throw new IllegalArgumentException("unknown mall: " + mall);
        }
    }

    public synchronized void failNextCalls(String mall, int count) {
        failuresToSimulate.put(mall, count);
    }

    private void failIfAsked(String mall) {
        int remaining = failuresToSimulate.getOrDefault(mall, 0);
        if (remaining > 0) {
            failuresToSimulate.put(mall, remaining - 1);
            throw new Unavailable(mall);
        }
    }

    private ArrayNode filter(ArrayNode orders, Function<JsonNode, Instant> updatedAt, Instant after) {
        ArrayNode result = json.createArrayNode();
        for (JsonNode order : orders) {
            if (!updatedAt.apply(order).isBefore(after)) {
                result.add(order.deepCopy());
            }
        }
        return result;
    }

    private static ObjectNode find(ArrayNode orders, String key, String id) {
        for (JsonNode order : orders) {
            if (order.path(key).asText().equals(id)) {
                return (ObjectNode) order;
            }
        }
        throw new IllegalArgumentException("no order " + id);
    }

    private ArrayNode load(String resource) {
        try (InputStream in = MockMalls.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing " + resource);
            }
            return (ArrayNode) json.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
