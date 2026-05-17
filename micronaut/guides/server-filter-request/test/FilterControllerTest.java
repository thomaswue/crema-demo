package guides.serverfilterrequest;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@MicronautTest
final class FilterControllerTest {
    @Inject
    @Client("/")
    HttpClient client;

    @Test
    void responseFilterAddsHeader() {
        HttpResponse<String> response = client.toBlocking().exchange(
                HttpRequest.GET("/guide/filter"),
                String.class
        );

        assertEquals("filter guide", response.body());
        assertEquals("applied", response.header("X-Guide-Filter"));
    }
}
