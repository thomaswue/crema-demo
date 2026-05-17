package examples.client;

import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Produces;

@Controller("/client-target")
public final class TargetController {
    @Get("/{name}")
    @Produces(MediaType.TEXT_PLAIN)
    String greet(String name) {
        return "client hello " + name;
    }
}
