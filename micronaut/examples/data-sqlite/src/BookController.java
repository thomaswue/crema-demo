package examples.datasqlite;

import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Body;

import java.util.List;

@Controller("/books")
public final class BookController {
    private final BookRepository repository;

    BookController(BookRepository repository) {
        this.repository = repository;
    }

    @Get
    List<Book> list() {
        return repository.findAllOrderById();
    }

    @Get("/{id}")
    Book get(Long id) {
        return repository.findById(id).orElseThrow();
    }

    @Post
    Book save(@Body Book book) {
        repository.insert(book.id(), book.title(), book.pages());
        return repository.findById(book.id()).orElseThrow();
    }
}
