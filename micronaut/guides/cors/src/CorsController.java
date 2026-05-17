package guides.cors;

import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Produces;

@Controller("/guide/cors")
public final class CorsController {
    @Get
    @Produces(MediaType.TEXT_PLAIN)
    String index() {
        return "cors guide";
    }
}
