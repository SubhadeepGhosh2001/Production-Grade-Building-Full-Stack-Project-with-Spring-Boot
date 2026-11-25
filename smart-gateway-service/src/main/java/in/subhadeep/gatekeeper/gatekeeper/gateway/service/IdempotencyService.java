package in.subhadeep.gatekeeper.gatekeeper.gateway.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Service
public class IdempotencyService {

    private static final Duration RESPONSE_TTL = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;

    public IdempotencyService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public Optional<Replay> replay(String organizationId, String routeId, String key) {
        if(key == null || key.isBlank()) {
            return Optional.empty();
        }

        String redisKey = redisKey(organizationId, routeId, key);
        Map<Object, Object> values = redisTemplate.opsForHash().entries(redisKey);
        if(values.isEmpty()) {
            return Optional.empty();
        }

        try {
            int status = Integer.parseInt(String.valueOf(values.get("status")));
            byte[] body = Base64.getDecoder().decode(String.valueOf(values.get("body")));
            String contentType = String.valueOf(values.getOrDefault("contentType", ""));
            return Optional.of(new Replay(status, body, contentType));
        } catch (RuntimeException exception) {
            redisTemplate.delete(redisKey);
            return Optional.empty();
        }
    }

    public void save(String organizationId, String routeId, String key,
                     int status, byte[] body, String contentType) {
        if(key == null || key.isBlank()) {
            return;
        }

        String redisKey = redisKey(organizationId, routeId, key);
        Map<String, String> values = new HashMap<>();
        values.put("status", String.valueOf(status));
        values.put("body", Base64.getEncoder().encodeToString(body == null ? new byte[0] : body));
        values.put("contentType", contentType == null ? "" : contentType);

        redisTemplate.opsForHash().putAll(redisKey, values);
        redisTemplate.expire(redisKey, RESPONSE_TTL);
    }

    private String redisKey(String organizationId, String routeId, String key) {
        return "gateway:idempotency:" + organizationId + ":" + routeId + ":" +
                key.replace(':', '_');
    }

    public record Replay(int status, byte[] body, String contentType) {
    }
}
