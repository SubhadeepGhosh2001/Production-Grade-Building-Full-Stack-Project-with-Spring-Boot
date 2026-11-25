package in.subhadeep.gatekeeper.gatekeeper.gateway.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import org.springframework.web.util.pattern.PathPattern;

@AllArgsConstructor
@Getter
@Setter
public class CompiledRoute {
    private GatewayRoute gatewayRoute;
    private PathPattern pathPattern;
    private String sourcePathPrefix;

}
