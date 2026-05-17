package guides.creatingfirstapp;

import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Produces;

@Controller("/guide/hello")
public final class HelloController {
    @Get
    @Produces(MediaType.TEXT_PLAIN)
    String index() {
        return "hello guide";
    }
}
