package vn.edu.hcmut.tmc.kiosk.infrastructure.config;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import vn.edu.hcmut.tmc.kiosk.config.KioskProperties;

/** Two independent HTTP clients: local turnstile (LAN) and central (WAN). Both always have explicit timeouts. */
@Configuration
public class RestClientConfig {

    @Bean("centralRestClient")
    RestClient centralRestClient(KioskProperties properties) {
        KioskProperties.Central central = properties.central();
        return build(central.baseUrl(), central.connectTimeoutMs(), central.readTimeoutMs());
    }

    @Bean("turnstileRestClient")
    RestClient turnstileRestClient(KioskProperties properties) {
        KioskProperties.Turnstile turnstile = properties.turnstile();
        return build(turnstile.baseUrl(), turnstile.timeoutMs(), turnstile.timeoutMs());
    }

    /** HTTP/1.1 is forced: the JDK client's default h2c upgrade attempt confuses simple HTTP servers. */
    public static RestClient build(String baseUrl, int connectTimeoutMs, int readTimeoutMs) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        return RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }
}
