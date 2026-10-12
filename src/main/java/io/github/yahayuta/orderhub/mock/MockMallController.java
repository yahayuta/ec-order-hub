package io.github.yahayuta.orderhub.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the fictional marketplaces under {@code /mock} when the {@code mock} profile is on, so
 * the whole flow runs with {@code docker compose up} and no external service.
 */
@RestController
@RequestMapping("/mock")
@Profile("mock")
public class MockMallController {

    @Configuration
    @Profile("mock")
    static class MockConfig {
        @Bean
        MockMalls mockMalls(ObjectMapper json) {
            return new MockMalls(json);
        }
    }

    private final MockMalls malls;

    public MockMallController(MockMalls malls) {
        this.malls = malls;
    }

    @GetMapping(value = "/sakura/orders", produces = MediaType.APPLICATION_JSON_VALUE)
    public String sakura(@RequestParam("updated_after") String updatedAfter) {
        return malls.sakuraOrders(updatedAfter);
    }

    @GetMapping(value = "/komorebi/orders", produces = MediaType.APPLICATION_JSON_VALUE)
    public String komorebi(@RequestParam("since") String since) {
        return malls.komorebiOrders(since);
    }

    @PostMapping("/{mall}/orders/{id}/cancel")
    public void cancel(@PathVariable String mall, @PathVariable String id) {
        malls.cancel(mall, id, Instant.now());
    }

    @PostMapping("/{mall}/faults")
    public void fail(@PathVariable String mall, @RequestParam(defaultValue = "1") int count) {
        malls.failNextCalls(mall, count);
    }

    @ExceptionHandler(MockMalls.Unavailable.class)
    ResponseEntity<String> unavailable(MockMalls.Unavailable e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(e.getMessage());
    }
}
