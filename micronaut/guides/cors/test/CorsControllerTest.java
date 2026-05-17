package guides.cors;

import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@MicronautTest
final class CorsControllerTest {
    @Inject
    @Client("/")
    HttpClient client;

    @Test
    void addsCorsHeadersToCrossOriginResponse() {
        HttpResponse<String> response = client.toBlocking().exchange(
                HttpRequest.GET("/guide/cors")
                        .header(HttpHeaders.ORIGIN, "https://example.com"),
                String.class
        );

        assertEquals("cors guide", response.body());
        assertEquals("https://example.com", response.header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }
}
