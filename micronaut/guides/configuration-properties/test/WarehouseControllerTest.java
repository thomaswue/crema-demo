package guides.configurationproperties;

import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@MicronautTest
final class WarehouseControllerTest {
    @Inject
    @Client("/")
    HttpClient client;

    @Test
    void usesApplicationYamlAndTestEnvironmentOverride() {
        WarehouseController.Summary summary = client.toBlocking().retrieve(
                "/guide/configuration",
                WarehouseController.Summary.class
        );

        assertEquals("guide-test-warehouse", summary.name());
        assertEquals(List.of("ambient", "frozen"), summary.zones());
        assertEquals(42, summary.maxItems());
        assertEquals(true, summary.refrigerated());
    }
}
