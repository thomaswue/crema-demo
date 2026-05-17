package examples.configuration;

import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;

import java.util.List;

@Controller("/warehouse")
public final class WarehouseController {
    private final WarehouseConfiguration configuration;

    WarehouseController(WarehouseConfiguration configuration) {
        this.configuration = configuration;
    }

    @Get("/summary")
    Summary summary() {
        WarehouseConfiguration.Limits limits = configuration.getLimits();
        return new Summary(
                configuration.getName(),
                configuration.getZones(),
                limits.getMaxItems(),
                limits.isRefrigerated()
        );
    }

    public record Summary(String name, List<String> zones, int maxItems, boolean refrigerated) {
    }
}
