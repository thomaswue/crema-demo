package guides.errorhandling;

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
final class ProblemControllerTest {
    @Inject
    @Client("/")
    HttpClient client;

    @Test
    void mapsExceptionToErrorResponse() {
        HttpClientResponseException exception = assertThrows(
                HttpClientResponseException.class,
                () -> client.toBlocking().retrieve("/guide/errors/boom")
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        assertEquals(
                new ProblemController.ErrorBody("invalid", "bad guide input"),
                exception.getResponse().getBody(ProblemController.ErrorBody.class).orElseThrow()
        );
    }
}
