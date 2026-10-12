package io.github.yahayuta.orderhub.importer;

import io.github.yahayuta.orderhub.domain.Order;
import io.github.yahayuta.orderhub.domain.OrderLine;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class OrderRepository {

    public enum Change { INSERTED, UPDATED, UNCHANGED }

    public record OrderRow(long id, String mall, String externalId, Instant orderedAt, String status,
                           String prefecture, long statedTotal, long linesTotal, boolean needsReview,
                           String issues) {
    }

    public record LineRow(int lineNo, String sku, int quantity, long unitPrice) {
    }

    private final JdbcClient jdbc;

    public OrderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts the order, or updates it only if its content changed. The WHERE on the conflict
     * branch makes an unchanged re-import return no row, and {@code xmax = 0} tells a fresh insert
     * from an update, so one statement answers "what happened" without a read first.
     */
    @Transactional
    public Change upsert(Order order) {
        Optional<long[]> written = jdbc.sql("""
                        INSERT INTO orders (mall, external_id, ordered_at, mall_updated_at, status, prefecture,
                                            stated_total, lines_total, needs_review, issues, content_hash)
                        VALUES (:mall, :externalId, :orderedAt, :mallUpdatedAt, :status, :prefecture,
                                :statedTotal, :linesTotal, :needsReview, :issues, :hash)
                        ON CONFLICT (mall, external_id) DO UPDATE SET
                            ordered_at = EXCLUDED.ordered_at,
                            mall_updated_at = EXCLUDED.mall_updated_at,
                            status = EXCLUDED.status,
                            prefecture = EXCLUDED.prefecture,
                            stated_total = EXCLUDED.stated_total,
                            lines_total = EXCLUDED.lines_total,
                            needs_review = EXCLUDED.needs_review,
                            issues = EXCLUDED.issues,
                            content_hash = EXCLUDED.content_hash,
                            last_changed_at = now()
                        WHERE orders.content_hash <> EXCLUDED.content_hash
                        RETURNING id, (xmax = 0) AS inserted
                        """)
                .param("mall", order.mall())
                .param("externalId", order.externalId())
                .param("orderedAt", utc(order.orderedAt()))
                .param("mallUpdatedAt", utc(order.mallUpdatedAt()))
                .param("status", order.status().name())
                .param("prefecture", order.prefecture())
                .param("statedTotal", order.statedTotal())
                .param("linesTotal", order.linesTotal())
                .param("needsReview", order.needsReview())
                .param("issues", String.join("; ", order.issues()))
                .param("hash", order.contentHash())
                .query((rs, n) -> new long[] {rs.getLong("id"), rs.getBoolean("inserted") ? 1 : 0})
                .optional();
        if (written.isEmpty()) {
            return Change.UNCHANGED;
        }
        long id = written.get()[0];
        jdbc.sql("DELETE FROM order_lines WHERE order_id = :id").param("id", id).update();
        List<OrderLine> lines = order.lines();
        for (int i = 0; i < lines.size(); i++) {
            jdbc.sql("""
                            INSERT INTO order_lines (order_id, line_no, sku, quantity, unit_price)
                            VALUES (:id, :lineNo, :sku, :quantity, :unitPrice)
                            """)
                    .param("id", id)
                    .param("lineNo", i + 1)
                    .param("sku", lines.get(i).sku())
                    .param("quantity", lines.get(i).quantity())
                    .param("unitPrice", lines.get(i).unitPrice())
                    .update();
        }
        return written.get()[1] == 1 ? Change.INSERTED : Change.UPDATED;
    }

    public Optional<Instant> cursor(String mall) {
        return jdbc.sql("SELECT cursor_at FROM import_cursors WHERE mall = :mall")
                .param("mall", mall)
                .query((rs, n) -> instant(rs, "cursor_at"))
                .optional();
    }

    public void saveCursor(String mall, Instant cursorAt) {
        jdbc.sql("""
                        INSERT INTO import_cursors (mall, cursor_at) VALUES (:mall, :cursorAt)
                        ON CONFLICT (mall) DO UPDATE SET cursor_at = EXCLUDED.cursor_at
                        """)
                .param("mall", mall)
                .param("cursorAt", utc(cursorAt))
                .update();
    }

    public List<OrderRow> find(String mall, String status, Boolean needsReview, int limit) {
        return jdbc.sql("""
                        SELECT id, mall, external_id, ordered_at, status, prefecture, stated_total, lines_total,
                               needs_review, issues
                        FROM orders
                        WHERE (CAST(:mall AS TEXT) IS NULL OR mall = :mall)
                          AND (CAST(:status AS TEXT) IS NULL OR status = :status)
                          AND (CAST(:needsReview AS BOOLEAN) IS NULL OR needs_review = :needsReview)
                        ORDER BY ordered_at DESC, id DESC
                        LIMIT :limit
                        """)
                .param("mall", mall)
                .param("status", status)
                .param("needsReview", needsReview)
                .param("limit", limit)
                .query(OrderRepository::orderRow)
                .list();
    }

    public Optional<OrderRow> findById(long id) {
        return jdbc.sql("""
                        SELECT id, mall, external_id, ordered_at, status, prefecture, stated_total, lines_total,
                               needs_review, issues
                        FROM orders WHERE id = :id
                        """)
                .param("id", id)
                .query(OrderRepository::orderRow)
                .optional();
    }

    public List<LineRow> lines(long orderId) {
        return jdbc.sql("""
                        SELECT line_no, sku, quantity, unit_price FROM order_lines
                        WHERE order_id = :id ORDER BY line_no
                        """)
                .param("id", orderId)
                .query((rs, n) -> new LineRow(rs.getInt("line_no"), rs.getString("sku"),
                        rs.getInt("quantity"), rs.getLong("unit_price")))
                .list();
    }

    public Optional<OrderRow> findByExternalId(String mall, String externalId) {
        return jdbc.sql("""
                        SELECT id, mall, external_id, ordered_at, status, prefecture, stated_total, lines_total,
                               needs_review, issues
                        FROM orders WHERE mall = :mall AND external_id = :externalId
                        """)
                .param("mall", mall)
                .param("externalId", externalId)
                .query(OrderRepository::orderRow)
                .optional();
    }

    private static OrderRow orderRow(ResultSet rs, int rowNum) throws SQLException {
        return new OrderRow(rs.getLong("id"), rs.getString("mall"), rs.getString("external_id"),
                instant(rs, "ordered_at"), rs.getString("status"), rs.getString("prefecture"),
                rs.getLong("stated_total"), rs.getLong("lines_total"), rs.getBoolean("needs_review"),
                rs.getString("issues"));
    }

    static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }
}
