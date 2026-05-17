package dependencydemo;

import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Produces;
import org.apache.commons.text.StringEscapeUtils;

@Controller("/dependency")
public final class DependencyController {
    @Get("/escape")
    @Produces(MediaType.TEXT_PLAIN)
    String escape() {
        return StringEscapeUtils.escapeHtml4("<crema>");
    }
}
