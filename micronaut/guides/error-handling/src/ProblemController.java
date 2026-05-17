package guides.errorhandling;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Error;
import io.micronaut.http.annotation.Get;

@Controller("/guide/errors")
public final class ProblemController {
    @Get("/boom")
    String boom() {
        throw new IllegalArgumentException("bad guide input");
    }

    @Error(exception = IllegalArgumentException.class)
    HttpResponse<ErrorBody> badRequest(IllegalArgumentException exception) {
        return HttpResponse.badRequest(new ErrorBody("invalid", exception.getMessage()));
    }

    public record ErrorBody(String code, String message) {
    }
}
