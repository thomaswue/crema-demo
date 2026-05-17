package examples.datasqlite;

import io.micronaut.data.connection.annotation.Connectable;
import io.micronaut.data.annotation.Query;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

import java.util.List;

@JdbcRepository(dialect = Dialect.H2)
@Connectable(readOnly = false)
public interface BookRepository extends CrudRepository<Book, Long> {
    List<Book> findAllOrderById();

    @Query("INSERT INTO book (id, title, pages) VALUES (:id, :title, :pages)")
    void insert(Long id, String title, int pages);
}
