package guides.contentnegotiation;

import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;

@Controller("/guide/negotiation")
public final class NegotiationController {
    @Get(produces = MediaType.TEXT_PLAIN)
    String text() {
        return "guide as text";
    }

    @Get(produces = MediaType.APPLICATION_JSON)
    Message json() {
        return new Message("guide as json");
    }

    public record Message(String message) {
    }
}
