package io.github.yahayuta.orderhub.mall;

import java.time.Duration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

final class HttpClients {

    private HttpClients() {
    }

    static RestClient create(RestClient.Builder builder, String baseUrl, Duration timeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        return builder.clone().baseUrl(baseUrl).requestFactory(factory).build();
    }
}
