package examples.client;

import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Produces;

import java.util.concurrent.CompletionStage;

@Controller("/client")
public final class ClientController {
    private final LocalGreetingClient client;

    ClientController(LocalGreetingClient client) {
        this.client = client;
    }

    @Get("/greet/{name}")
    @Produces(MediaType.TEXT_PLAIN)
    CompletionStage<String> greet(String name) {
        return client.greet(name);
    }
}
