package in.subhadeep.gatekeeper.gatekeeper.gateway.service;


import in.subhadeep.gatekeeper.gatekeeper.gateway.client.ManagementApiClient;
import in.subhadeep.gatekeeper.gatekeeper.gateway.dto.GatewayRoute;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class GatewayConfigurationLoader implements ApplicationRunner {

    private final ManagementApiClient managementApiClient;
    private final RouteRegistry routeRegistry;

    public GatewayConfigurationLoader(ManagementApiClient managementApiClient,
                                     RouteRegistry routeRegistry) {
        this.managementApiClient = managementApiClient;
        this.routeRegistry = routeRegistry;
    }

    @Override
    public void run(@NonNull ApplicationArguments arguments) {
        refresh();
    }

    public void refresh() {
        try {
            List<GatewayRoute> routes = managementApiClient.fetchActiveRoutes();
            routeRegistry.replaceAll(routes);
        } catch (RuntimeException exception) {
            System.err.println("Unable to refresh gateway routes: " + exception.getMessage());
        }
    }
}