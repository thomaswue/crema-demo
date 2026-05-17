package examples.json;

import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;

@Controller("/message")
public final class MessageController {
    @Get("/{text}")
    Message message(String text) {
        return new Message(text, text.length());
    }

    public record Message(String text, int length) {
    }
}
