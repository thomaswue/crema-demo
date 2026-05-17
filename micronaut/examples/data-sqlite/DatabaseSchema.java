package examples.datasqlite;

import io.micronaut.context.annotation.Context;
import io.micronaut.data.connection.annotation.Connectable;
import io.micronaut.runtime.event.ApplicationStartupEvent;
import io.micronaut.runtime.event.annotation.EventListener;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

@Context
public class DatabaseSchema {
    private final DataSource dataSource;

    DatabaseSchema(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Connectable
    @EventListener
    void initialize(ApplicationStartupEvent event) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS book (
                        id INTEGER PRIMARY KEY,
                        title TEXT NOT NULL,
                        pages INTEGER NOT NULL
                    )
                    """);
            statement.executeUpdate("DELETE FROM book");
            statement.executeUpdate("""
                    INSERT INTO book (id, title, pages)
                    VALUES (1, 'Source Launchers', 42)
                    """);
        }
    }
}
