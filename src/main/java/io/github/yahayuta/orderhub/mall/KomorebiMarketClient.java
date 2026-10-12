package io.github.yahayuta.orderhub.mall;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.yahayuta.orderhub.domain.Normalize;
import io.github.yahayuta.orderhub.domain.Order;
import io.github.yahayuta.orderhub.domain.OrderLine;
import io.github.yahayuta.orderhub.domain.OrderStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * "Komorebi Market" (fictional): an older-style API. Times are Japan local time without an
 * offset, amounts are strings such as "1,980円", and product codes may be full-width.
 *
 * <pre>GET /orders?since=2026/10/01 09:00:00
 * {"data": [{"id": "KM-0001", "created": "2026/10/01 10:05:00", "modified": "...",
 *            "state": "paid", "pref": "東京都",
 *            "lines": [{"item_code": "ＭＵＧ－ＲＥＤ－０１", "quantity": "１", "price": "1,980円"}],
 *            "amount": "1,980円"}], "count": 1}</pre>
 */
@Component
public class KomorebiMarketClient implements MallClient {

    public static final String CODE = "komorebi";

    private final RestClient http;

    public KomorebiMarketClient(RestClient.Builder builder, OrderHubProperties properties) {
        this.http = HttpClients.create(builder, properties.baseUrl(CODE), properties.timeout(CODE));
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public List<Order> fetchUpdatedSince(Instant since) {
        String sinceText = Normalize.toSlashedJst(since);
        JsonNode body = http.get()
                .uri(uri -> uri.path("/orders").queryParam("since", "{since}").build(sinceText))
                .retrieve()
                .body(JsonNode.class);
        List<Order> orders = new ArrayList<>();
        if (body != null) {
            body.path("data").forEach(node -> orders.add(toOrder(node)));
        }
        return orders;
    }

    static Order toOrder(JsonNode node) {
        List<String> issues = new ArrayList<>();
        List<OrderLine> lines = new ArrayList<>();
        int lineNo = 0;
        for (JsonNode line : node.path("lines")) {
            lineNo++;
            try {
                lines.add(new OrderLine(Normalize.sku(line.path("item_code").asText()),
                        Normalize.quantity(line.path("quantity").asText()),
                        Normalize.yen(line.path("price").asText())));
            } catch (IllegalArgumentException e) {
                issues.add("line %d: could not read quantity or price (%s)".formatted(lineNo, e.getMessage()));
            }
        }
        long total;
        try {
            total = Normalize.yen(node.path("amount").asText());
        } catch (IllegalArgumentException e) {
            issues.add("could not read amount \"%s\"".formatted(node.path("amount").asText()));
            total = -1;
        }
        String rawState = node.path("state").asText();
        OrderStatus status = switch (rawState) {
            case "paid" -> OrderStatus.NEW;
            case "sent" -> OrderStatus.SHIPPED;
            case "canceled" -> OrderStatus.CANCELLED;
            default -> {
                issues.add("unknown state \"%s\"".formatted(rawState));
                yield OrderStatus.UNKNOWN;
            }
        };
        return Order.of(CODE, Normalize.text(node.path("id").asText()),
                Normalize.slashedJst(node.path("created").asText()),
                Normalize.slashedJst(node.path("modified").asText()),
                status, Normalize.text(node.path("pref").asText()), lines, total, issues);
    }
}
