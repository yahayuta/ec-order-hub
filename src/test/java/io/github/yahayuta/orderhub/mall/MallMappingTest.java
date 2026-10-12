package io.github.yahayuta.orderhub.mall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yahayuta.orderhub.domain.Order;
import io.github.yahayuta.orderhub.domain.OrderLine;
import io.github.yahayuta.orderhub.domain.OrderStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

class MallMappingTest {

    private final ObjectMapper json = new ObjectMapper();

    private JsonNode read(String text) throws Exception {
        return json.readTree(text);
    }

    @Test
    void sakuraOrdersMapStraightAcross() throws Exception {
        Order order = SakuraMallClient.toOrder(read("""
                {"order_no": "SK-1", "ordered_at": "2026-10-01T09:12:00+09:00",
                 "updated_at": "2026-10-01T09:30:00+09:00", "status": "SHIPPED",
                 "shipping": {"prefecture": "神奈川県"},
                 "items": [{"sku": "mug-blu-01", "qty": 2, "unit_price": 1980}], "total": 3960}
                """));
        assertThat(order.externalId()).isEqualTo("SK-1");
        assertThat(order.orderedAt()).isEqualTo(Instant.parse("2026-10-01T00:12:00Z"));
        assertThat(order.status()).isEqualTo(OrderStatus.SHIPPED);
        assertThat(order.lines()).containsExactly(new OrderLine("MUG-BLU-01", 2, 1980));
        assertThat(order.needsReview()).isFalse();
    }

    @Test
    void komorebiFullWidthTextAndYenStringsAreNormalised() throws Exception {
        Order order = KomorebiMarketClient.toOrder(read("""
                {"id": "KM-1", "created": "2026/10/01 10:05:00", "modified": "2026/10/01 10:05:00",
                 "state": "paid", "pref": "東京都",
                 "lines": [{"item_code": "ＭＵＧ－ＲＥＤ－０１", "quantity": "２", "price": "1,980円"}],
                 "amount": "3,960円"}
                """));
        assertThat(order.orderedAt()).isEqualTo(Instant.parse("2026-10-01T01:05:00Z"));
        assertThat(order.status()).isEqualTo(OrderStatus.NEW);
        assertThat(order.lines()).containsExactly(new OrderLine("MUG-RED-01", 2, 1980));
        assertThat(order.statedTotal()).isEqualTo(3960);
        assertThat(order.needsReview()).isFalse();
    }

    @Test
    void komorebiUnknownStateAndUnreadableAmountsAreFlaggedNotDropped() throws Exception {
        Order order = KomorebiMarketClient.toOrder(read("""
                {"id": "KM-2", "created": "2026/10/01 10:05:00", "modified": "2026/10/01 10:05:00",
                 "state": "on_hold", "pref": "",
                 "lines": [{"item_code": "A", "quantity": "1", "price": "ask the shop"}],
                 "amount": "1,000円"}
                """));
        assertThat(order.status()).isEqualTo(OrderStatus.UNKNOWN);
        assertThat(order.issues()).anyMatch(i -> i.startsWith("line 1: could not read"))
                .contains("unknown state \"on_hold\"");
    }

    @Test
    void retryRetriesServerErrorsWithGrowingBackoff() {
        AtomicInteger calls = new AtomicInteger();
        List<Duration> waits = new ArrayList<>();
        Retry.Outcome<String> outcome = Retry.call(() -> {
            if (calls.incrementAndGet() < 3) {
                throw new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE);
            }
            return "ok";
        }, 3, Duration.ofMillis(100), e -> e instanceof HttpServerErrorException, waits::add);
        assertThat(outcome.value()).isEqualTo("ok");
        assertThat(outcome.attempts()).isEqualTo(3);
        assertThat(waits).containsExactly(Duration.ofMillis(100), Duration.ofMillis(200));
    }

    @Test
    void retryGivesUpAtOnceOnAClientError() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> Retry.call(() -> {
            calls.incrementAndGet();
            throw new HttpClientErrorException(HttpStatus.BAD_REQUEST);
        }, 3, Duration.ZERO, e -> e instanceof HttpServerErrorException, d -> { }))
                .isInstanceOf(Retry.GaveUp.class);
        assertThat(calls).hasValue(1);
    }
}
