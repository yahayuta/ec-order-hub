package io.github.yahayuta.orderhub.api;

import io.github.yahayuta.orderhub.importer.ImportRun;
import io.github.yahayuta.orderhub.importer.ImportRunRepository;
import io.github.yahayuta.orderhub.importer.ImportService;
import io.github.yahayuta.orderhub.importer.OrderRepository;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class OrderController {

    public record OrderDetail(OrderRepository.OrderRow order, List<OrderRepository.LineRow> lines) {
    }

    private final OrderRepository orders;
    private final ImportRunRepository runs;
    private final ImportService importer;

    public OrderController(OrderRepository orders, ImportRunRepository runs, ImportService importer) {
        this.orders = orders;
        this.runs = runs;
        this.importer = importer;
    }

    /** e.g. {@code GET /api/orders?needsReview=true} for the review queue. */
    @GetMapping("/orders")
    public List<OrderRepository.OrderRow> list(@RequestParam(required = false) String mall,
                                               @RequestParam(required = false) String status,
                                               @RequestParam(required = false) Boolean needsReview,
                                               @RequestParam(defaultValue = "100") int limit) {
        return orders.find(mall, status, needsReview, Math.min(Math.max(limit, 1), 500));
    }

    @GetMapping("/orders/{id}")
    public OrderDetail get(@PathVariable long id) {
        OrderRepository.OrderRow order = orders.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return new OrderDetail(order, orders.lines(id));
    }

    @PostMapping("/imports/{mall}")
    public ImportRun runImport(@PathVariable String mall) {
        if (!importer.malls().contains(mall)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown mall: " + mall);
        }
        return importer.importMall(mall);
    }

    @GetMapping("/imports")
    public List<ImportRun> recentImports(@RequestParam(defaultValue = "20") int limit) {
        return runs.latest(Math.min(Math.max(limit, 1), 200));
    }
}
