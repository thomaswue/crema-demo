package examples.datasqlite;

import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;

@MappedEntity("book")
public record Book(
        @Id Long id,
        String title,
        int pages
) {
}
