package guides.staticresources;

import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Produces;

@Controller("/guide/static")
public final class StaticResourceController {
    @Get
    @Produces(MediaType.TEXT_PLAIN)
    String status() {
        return "static resources enabled";
    }
}
