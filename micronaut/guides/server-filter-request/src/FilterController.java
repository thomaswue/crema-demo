package guides.serverfilterrequest;

import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Produces;

@Controller("/guide/filter")
public final class FilterController {
    @Get
    @Produces(MediaType.TEXT_PLAIN)
    String index() {
        return "filter guide";
    }
}
