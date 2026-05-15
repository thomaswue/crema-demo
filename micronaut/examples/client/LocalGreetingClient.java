package examples.client;

import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Consumes;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.client.annotation.Client;

import java.util.concurrent.CompletableFuture;

@Client("/")
public interface LocalGreetingClient {
    @Get(value = "/client-target/{name}", produces = MediaType.TEXT_PLAIN)
    @Consumes(MediaType.TEXT_PLAIN)
    @Produces(MediaType.TEXT_PLAIN)
    CompletableFuture<String> greet(String name);
}
