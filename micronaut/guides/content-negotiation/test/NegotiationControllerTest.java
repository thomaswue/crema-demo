package guides.contentnegotiation;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@MicronautTest
final class NegotiationControllerTest {
    @Inject
    @Client("/")
    HttpClient client;

    @Test
    void returnsTextWhenTextIsAccepted() {
        String response = client.toBlocking().retrieve(
                HttpRequest.GET("/guide/negotiation").accept(MediaType.TEXT_PLAIN_TYPE),
                String.class
        );

        assertEquals("guide as text", response);
    }

    @Test
    void returnsJsonWhenJsonIsAccepted() {
        NegotiationController.Message response = client.toBlocking().retrieve(
                HttpRequest.GET("/guide/negotiation").accept(MediaType.APPLICATION_JSON_TYPE),
                NegotiationController.Message.class
        );

        assertEquals(new NegotiationController.Message("guide as json"), response);
    }
}
