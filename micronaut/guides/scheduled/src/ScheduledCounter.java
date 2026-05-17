package guides.scheduled;

import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;

import java.util.concurrent.atomic.AtomicInteger;

@Singleton
public final class ScheduledCounter {
    private final AtomicInteger ticks = new AtomicInteger();

    @Scheduled(fixedDelay = "10ms", initialDelay = "10ms")
    void tick() {
        ticks.incrementAndGet();
    }

    int ticks() {
        return ticks.get();
    }
}
