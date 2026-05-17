package guides.validation;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@MicronautTest
final class BookControllerTest {
    @Inject
    @Client("/")
    HttpClient client;

    @Test
    void validatesJsonBody() {
        BookController.BookResponse response = client.toBlocking().retrieve(
                HttpRequest.POST("/guide/books", new BookSaveCommand(" Native Micronaut ", 123)),
                BookController.BookResponse.class
        );

        assertEquals(new BookController.BookResponse("Native Micronaut", 123), response);
    }

    @Test
    void rejectsInvalidJsonBody() {
        HttpClientResponseException exception = assertThrows(
                HttpClientResponseException.class,
                () -> client.toBlocking().exchange(HttpRequest.POST("/guide/books", new BookSaveCommand("", 0)))
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
    }
}
