package guides.scheduled;

import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;

@Controller("/guide/scheduled")
public final class ScheduledController {
    private final ScheduledCounter counter;

    ScheduledController(ScheduledCounter counter) {
        this.counter = counter;
    }

    @Get
    Count count() {
        return new Count(counter.ticks());
    }

    public record Count(int ticks) {
    }
}
