package io.github.yahayuta.orderhub.mall;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("orderhub")
public record OrderHubProperties(Map<String, Mall> malls, @DefaultValue Import importing) {

    public record Mall(String baseUrl, @DefaultValue("PT10S") Duration timeout) {
    }

    /**
     * @param overlap        how far before the last cursor to ask again, so late-arriving updates
     *                       are not missed; re-reading is safe because imports are idempotent
     * @param maxAttempts    attempts per marketplace call
     * @param initialBackoff wait before the second attempt; doubles each time
     */
    public record Import(@DefaultValue("PT5M") Duration overlap,
                         @DefaultValue("3") int maxAttempts,
                         @DefaultValue("PT1S") Duration initialBackoff) {
    }

    public String baseUrl(String mall) {
        Mall config = malls == null ? null : malls.get(mall);
        if (config == null || config.baseUrl() == null) {
            throw new IllegalStateException("orderhub.malls." + mall + ".base-url is not set");
        }
        return config.baseUrl();
    }

    public Duration timeout(String mall) {
        Mall config = malls == null ? null : malls.get(mall);
        return config == null ? Duration.ofSeconds(10) : config.timeout();
    }
}
