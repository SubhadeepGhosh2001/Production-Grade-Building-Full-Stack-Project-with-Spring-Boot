package in.subhadeep.gatekeeper.management.service;

import in.subhadeep.gatekeeper.management.dto.responseDto.HealthResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class HealthService {
    private final JdbcTemplate jdbcTemplate;

    public HealthService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public String getHealth() {
String databaseStatus= "Down";
        try {
         Integer result = jdbcTemplate.queryForObject("SELECT 1", Integer.class);

         if(Integer.valueOf(1).equals(result)) {
             databaseStatus = "Up";
         }
        } catch (Exception e) {
System.out.println("Unable to connect to database. Database is down: " + e.getMessage());
        }

        return databaseStatus;
    }
}
