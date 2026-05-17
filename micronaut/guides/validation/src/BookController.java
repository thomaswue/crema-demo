package guides.validation;

import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Consumes;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import jakarta.validation.Valid;

@Controller("/guide/books")
public class BookController {
    @Post
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    BookResponse save(@Valid @Body BookSaveCommand command) {
        return new BookResponse(command.title().trim(), command.pages());
    }

    public record BookResponse(String title, int pages) {
    }
}
