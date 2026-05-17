package examples.datasqlite;

import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@MicronautTest(transactional = false)
final class BookControllerTest {
    @Inject
    @Client("/")
    HttpClient client;

    @Test
    void readsSeededBookFromSqlite() {
        List<Book> books = client.toBlocking().retrieve(
                HttpRequest.GET("/books"),
                Argument.listOf(Book.class)
        );

        assertEquals(List.of(new Book(1L, "Source Launchers", 42)), books);
    }

    @Test
    void writesBookThroughMicronautDataRepository() {
        Book saved = client.toBlocking().retrieve(
                HttpRequest.POST("/books", new Book(2L, "SQLite", 3)),
                Book.class
        );
        Book fetched = client.toBlocking().retrieve("/books/2", Book.class);

        assertEquals(new Book(2L, "SQLite", 3), saved);
        assertEquals(saved, fetched);
    }
}
