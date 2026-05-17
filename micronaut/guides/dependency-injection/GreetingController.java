package guides.dependencyinjection;

import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Produces;

@Controller("/guide/injection")
public final class GreetingController {
    private final GreetingService greetingService;

    GreetingController(GreetingService greetingService) {
        this.greetingService = greetingService;
    }

    @Get("/{name}")
    @Produces(MediaType.TEXT_PLAIN)
    String greet(String name) {
        return greetingService.greet(name);
    }
}
