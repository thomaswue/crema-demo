package guides.scheduled;

import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

@MicronautTest
final class ScheduledControllerTest {
    @Inject
    @Client("/")
    HttpClient client;

    @Test
    void runsScheduledTask() throws InterruptedException {
        int ticks = 0;
        for (int i = 0; i < 20; i++) {
            ticks = client.toBlocking().retrieve("/guide/scheduled", ScheduledController.Count.class).ticks();
            if (ticks > 0) {
                break;
            }
            Thread.sleep(25);
        }

        assertTrue(ticks > 0, "scheduled task should tick at least once");
    }
}
