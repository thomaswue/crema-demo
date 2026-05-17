package examples.parameters;

import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Produces;

@Controller("/greet")
public final class GreetingController {
    @Get("/{name}")
    @Produces(MediaType.TEXT_PLAIN)
    String greet(String name) {
        return "hello " + name;
    }
}
