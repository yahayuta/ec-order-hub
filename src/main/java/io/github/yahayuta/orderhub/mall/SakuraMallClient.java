package io.github.yahayuta.orderhub.mall;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.yahayuta.orderhub.domain.Normalize;
import io.github.yahayuta.orderhub.domain.Order;
import io.github.yahayuta.orderhub.domain.OrderLine;
import io.github.yahayuta.orderhub.domain.OrderStatus;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * "Sakura Mall" (fictional): a tidy JSON API with ISO-8601 times and numeric amounts.
 *
 * <pre>GET /orders?updated_after=2026-10-01T09:00:00+09:00
 * {"orders": [{"order_no": "SK-240001", "ordered_at": "...", "updated_at": "...",
 *              "status": "NEW", "shipping": {"prefecture": "神奈川県"},
 *              "items": [{"sku": "MUG-BLU-01", "qty": 2, "unit_price": 1980}], "total": 3960}]}</pre>
 */
@Component
public class SakuraMallClient implements MallClient {

    public static final String CODE = "sakura";

    private final RestClient http;

    public SakuraMallClient(RestClient.Builder builder, OrderHubProperties properties) {
        this.http = HttpClients.create(builder, properties.baseUrl(CODE), properties.timeout(CODE));
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public List<Order> fetchUpdatedSince(Instant since) {
        String after = since.atZone(Normalize.JST).toOffsetDateTime().toString();
        JsonNode body = http.get()
                .uri(uri -> uri.path("/orders").queryParam("updated_after", "{after}").build(after))
                .retrieve()
                .body(JsonNode.class);
        List<Order> orders = new ArrayList<>();
        if (body != null) {
            body.path("orders").forEach(node -> orders.add(toOrder(node)));
        }
        return orders;
    }

    static Order toOrder(JsonNode node) {
        List<String> issues = new ArrayList<>();
        List<OrderLine> lines = new ArrayList<>();
        for (JsonNode item : node.path("items")) {
            lines.add(new OrderLine(Normalize.sku(item.path("sku").asText()),
                    item.path("qty").asInt(), item.path("unit_price").asLong()));
        }
        String rawStatus = node.path("status").asText();
        OrderStatus status = switch (rawStatus) {
            case "NEW" -> OrderStatus.NEW;
            case "SHIPPED" -> OrderStatus.SHIPPED;
            case "CANCELLED" -> OrderStatus.CANCELLED;
            default -> {
                issues.add("unknown status \"%s\"".formatted(rawStatus));
                yield OrderStatus.UNKNOWN;
            }
        };
        return Order.of(CODE, node.path("order_no").asText(),
                OffsetDateTime.parse(node.path("ordered_at").asText()).toInstant(),
                OffsetDateTime.parse(node.path("updated_at").asText()).toInstant(),
                status, Normalize.text(node.path("shipping").path("prefecture").asText()),
                lines, node.path("total").asLong(), issues);
    }
}
