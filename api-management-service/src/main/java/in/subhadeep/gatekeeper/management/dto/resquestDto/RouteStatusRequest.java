package in.subhadeep.gatekeeper.management.dto.resquestDto;


import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RouteStatusRequest {

    @NotNull
    private Boolean active;
}