package guides.staticresources;

import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@MicronautTest
final class StaticResourcesTest {
    @Inject
    @Client("/")
    HttpClient client;

    @Test
    void servesClasspathStaticResource() {
        assertEquals("hello static resource", client.toBlocking().retrieve("/guide/assets/hello.txt").trim());
    }
}
