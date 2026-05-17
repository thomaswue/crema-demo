package guides.configurationproperties;

import io.micronaut.context.annotation.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties("warehouse")
public final class WarehouseConfiguration {
    private String name;
    private List<String> zones = List.of();
    private Limits limits = new Limits();

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<String> getZones() {
        return zones;
    }

    public void setZones(List<String> zones) {
        this.zones = zones;
    }

    public Limits getLimits() {
        return limits;
    }

    public void setLimits(Limits limits) {
        this.limits = limits;
    }

    @ConfigurationProperties("limits")
    public static final class Limits {
        private int maxItems;
        private boolean refrigerated;

        public int getMaxItems() {
            return maxItems;
        }

        public void setMaxItems(int maxItems) {
            this.maxItems = maxItems;
        }

        public boolean isRefrigerated() {
            return refrigerated;
        }

        public void setRefrigerated(boolean refrigerated) {
            this.refrigerated = refrigerated;
        }
    }
}
