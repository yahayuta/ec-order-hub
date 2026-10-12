package io.github.yahayuta.orderhub.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * An order in the shared format, whichever marketplace it came from.
 *
 * <p>{@link #of} runs the checks that decide whether the order can flow on automatically or
 * needs a person: the lines must add up to the stated total, quantities must be positive and the
 * state must be one we know. Problems found while reading the marketplace format are passed in
 * as {@code mappingIssues} and kept with the others.
 */
public record Order(
        String mall,
        String externalId,
        Instant orderedAt,
        Instant mallUpdatedAt,
        OrderStatus status,
        String prefecture,
        List<OrderLine> lines,
        long statedTotal,
        List<String> issues) {

    public Order {
        lines = List.copyOf(lines);
        issues = List.copyOf(issues);
    }

    public static Order of(String mall, String externalId, Instant orderedAt, Instant mallUpdatedAt,
                           OrderStatus status, String prefecture, List<OrderLine> lines, long statedTotal,
                           List<String> mappingIssues) {
        List<String> issues = new ArrayList<>(mappingIssues);
        if (lines.isEmpty()) {
            issues.add("order has no lines");
        }
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).quantity() <= 0) {
                issues.add("line %d: quantity %d is not positive".formatted(i + 1, lines.get(i).quantity()));
            }
        }
        long linesTotal = lines.stream().mapToLong(OrderLine::amount).sum();
        if (linesTotal != statedTotal) {
            issues.add(String.format(Locale.ROOT, "lines add up to %,d but the order says %,d", linesTotal, statedTotal));
        }
        if (status == OrderStatus.UNKNOWN && mappingIssues.isEmpty()) {
            issues.add("unknown order status");
        }
        return new Order(mall, externalId, orderedAt, mallUpdatedAt, status, prefecture, lines, statedTotal, issues);
    }

    public long linesTotal() {
        return lines.stream().mapToLong(OrderLine::amount).sum();
    }

    public boolean needsReview() {
        return !issues.isEmpty();
    }

    /** Changes when anything we store changes, so re-imports of the same data are no-ops. */
    public String contentHash() {
        StringBuilder canonical = new StringBuilder()
                .append(mall).append('|').append(externalId).append('|').append(orderedAt).append('|')
                .append(status).append('|').append(prefecture).append('|').append(statedTotal);
        for (OrderLine line : lines) {
            canonical.append('|').append(line.sku()).append('x').append(line.quantity())
                    .append('@').append(line.unitPrice());
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
