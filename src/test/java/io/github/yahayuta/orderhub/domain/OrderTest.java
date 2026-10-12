package io.github.yahayuta.orderhub.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class OrderTest {

    private static final Instant AT = Instant.parse("2026-10-01T00:00:00Z");

    private static Order order(List<OrderLine> lines, long total, OrderStatus status) {
        return Order.of("test", "T-1", AT, AT, status, "東京都", lines, total, List.of());
    }

    @Test
    void anOrderThatAddsUpNeedsNoReview() {
        Order order = order(List.of(new OrderLine("A", 2, 1980), new OrderLine("B", 1, 400)), 4360, OrderStatus.NEW);
        assertThat(order.needsReview()).isFalse();
        assertThat(order.linesTotal()).isEqualTo(4360);
    }

    @Test
    void aTotalThatDoesNotMatchTheLinesIsFlagged() {
        Order order = order(List.of(new OrderLine("A", 2, 1200), new OrderLine("B", 1, 2000)), 5000, OrderStatus.NEW);
        assertThat(order.needsReview()).isTrue();
        assertThat(order.issues()).containsExactly("lines add up to 4,400 but the order says 5,000");
    }

    @Test
    void emptyOrdersAndNonPositiveQuantitiesAreFlagged() {
        assertThat(order(List.of(), 0, OrderStatus.NEW).issues()).contains("order has no lines");
        assertThat(order(List.of(new OrderLine("A", 0, 100)), 0, OrderStatus.NEW).issues())
                .contains("line 1: quantity 0 is not positive");
    }

    @Test
    void anUnknownStatusIsFlagged() {
        assertThat(order(List.of(new OrderLine("A", 1, 100)), 100, OrderStatus.UNKNOWN).needsReview()).isTrue();
    }

    @Test
    void theHashChangesOnlyWhenStoredContentChanges() {
        Order a = order(List.of(new OrderLine("A", 1, 100)), 100, OrderStatus.NEW);
        Order same = order(List.of(new OrderLine("A", 1, 100)), 100, OrderStatus.NEW);
        Order cancelled = order(List.of(new OrderLine("A", 1, 100)), 100, OrderStatus.CANCELLED);
        assertThat(a.contentHash()).isEqualTo(same.contentHash()).isNotEqualTo(cancelled.contentHash());
    }

    @Test
    void normaliseReadsTheWaysMarketplacesWriteNumbersAndTimes() {
        assertThat(Normalize.yen("1,980円")).isEqualTo(1980);
        assertThat(Normalize.yen("¥1,980")).isEqualTo(1980);
        assertThat(Normalize.yen("２，４００")).isEqualTo(2400);
        assertThat(Normalize.yen("1980")).isEqualTo(1980);
        assertThat(Normalize.sku("ｍｕｇ－ｒｅｄ－０１")).isEqualTo("MUG-RED-01");
        assertThat(Normalize.slashedJst("2026/10/01 09:00:00")).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
        assertThat(Normalize.toSlashedJst(Instant.parse("2026-10-01T00:00:00Z"))).isEqualTo("2026/10/01 09:00:00");
        assertThatThrownBy(() -> Normalize.yen("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Normalize.yen("about 2000")).isInstanceOf(IllegalArgumentException.class);
    }
}
