package guides.serverfilterrequest;

import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.ResponseFilter;
import io.micronaut.http.annotation.ServerFilter;

@ServerFilter("/guide/filter")
public final class GuideHeaderFilter {
    @ResponseFilter
    void addGuideHeader(MutableHttpResponse<?> response) {
        response.header("X-Guide-Filter", "applied");
    }
}
