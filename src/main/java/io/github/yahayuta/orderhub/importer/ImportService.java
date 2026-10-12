package io.github.yahayuta.orderhub.importer;

import io.github.yahayuta.orderhub.domain.Order;
import io.github.yahayuta.orderhub.mall.MallClient;
import io.github.yahayuta.orderhub.mall.OrderHubProperties;
import io.github.yahayuta.orderhub.mall.Retry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Pulls changed orders from one marketplace and stores them.
 *
 * <ul>
 *   <li><b>Incremental</b>: asks only for orders changed since the saved cursor, minus an overlap
 *       window so late updates are not missed.</li>
 *   <li><b>Idempotent</b>: the overlap means some orders come back again; the upsert stores them
 *       only if their content changed, so running an import twice is harmless.</li>
 *   <li><b>Safe on failure</b>: transient errors are retried with backoff; if all attempts fail the
 *       cursor is not moved, so the next run picks up the same orders.</li>
 * </ul>
 */
@Service
public class ImportService {

    private static final Logger log = LoggerFactory.getLogger(ImportService.class);

    private final Map<String, MallClient> clients;
    private final OrderRepository orders;
    private final ImportRunRepository runs;
    private final OrderHubProperties.Import settings;
    private final Retry.Sleeper sleeper;

    public ImportService(List<MallClient> clients, OrderRepository orders, ImportRunRepository runs,
                         OrderHubProperties properties) {
        this.clients = clients.stream().collect(Collectors.toMap(MallClient::code, Function.identity()));
        this.orders = orders;
        this.runs = runs;
        this.settings = properties.importing();
        this.sleeper = duration -> Thread.sleep(duration.toMillis());
    }

    public Set<String> malls() {
        return clients.keySet();
    }

    public ImportRun importMall(String mall) {
        MallClient client = clients.get(mall);
        if (client == null) {
            throw new IllegalArgumentException("unknown mall: " + mall);
        }
        Instant started = Instant.now();
        Instant cursor = orders.cursor(mall).orElse(Instant.EPOCH);
        Instant since = cursor.equals(Instant.EPOCH) ? cursor : cursor.minus(settings.overlap());

        Retry.Outcome<List<Order>> fetched;
        try {
            fetched = Retry.call(() -> client.fetchUpdatedSince(since), settings.maxAttempts(),
                    settings.initialBackoff(), ImportService::isRetryable, sleeper);
        } catch (Retry.GaveUp e) {
            log.warn("import {} failed: {}", mall, e.getMessage());
            return runs.save(new ImportRun(null, mall, ImportRun.Status.FAILED, started, Instant.now(),
                    0, 0, 0, 0, 0, e.attempts(), e.getMessage()));
        }

        int inserted = 0;
        int updated = 0;
        int unchanged = 0;
        int flagged = 0;
        Instant newCursor = cursor;
        for (Order order : fetched.value()) {
            switch (orders.upsert(order)) {
                case INSERTED -> inserted++;
                case UPDATED -> updated++;
                case UNCHANGED -> unchanged++;
            }
            if (order.needsReview()) {
                flagged++;
            }
            if (order.mallUpdatedAt().isAfter(newCursor)) {
                newCursor = order.mallUpdatedAt();
            }
        }
        orders.saveCursor(mall, newCursor);
        ImportRun run = runs.save(new ImportRun(null, mall, ImportRun.Status.SUCCEEDED, started, Instant.now(),
                fetched.value().size(), inserted, updated, unchanged, flagged, fetched.attempts(), ""));
        log.info("import {}: fetched={} inserted={} updated={} unchanged={} flagged={} attempts={}", mall,
                run.fetched(), inserted, updated, unchanged, flagged, run.attempts());
        return run;
    }

    static boolean isRetryable(RuntimeException e) {
        if (e instanceof ResourceAccessException) {
            return true;
        }
        if (e instanceof RestClientResponseException response) {
            int status = response.getStatusCode().value();
            return status >= 500 || status == 429;
        }
        return false;
    }
}
