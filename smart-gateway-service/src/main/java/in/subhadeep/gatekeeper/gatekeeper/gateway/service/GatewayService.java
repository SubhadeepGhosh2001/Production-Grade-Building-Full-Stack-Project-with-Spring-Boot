package in.subhadeep.gatekeeper.gatekeeper.gateway.service;
import in.subhadeep.gatekeeper.gatekeeper.gateway.dto.CompiledRoute;
import in.subhadeep.gatekeeper.gatekeeper.gateway.dto.GatewayErrorResponse;
import in.subhadeep.gatekeeper.gatekeeper.gateway.dto.GatewayRoute;
import in.subhadeep.gatekeeper.gatekeeper.gateway.dto.RateLimitResult;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Enumeration;
import java.util.Optional;
import java.util.UUID;

@Service
public class GatewayService {

    private final RouteRegistry routeRegistry;
    private final ObjectMapper objectMapper;
    private final RateLimitService rateLimitService;
    private final IdempotencyService idempotencyService;

    public GatewayService(RouteRegistry routeRegistry, ObjectMapper objectMapper,
                          RateLimitService rateLimitService) {
        this(routeRegistry, objectMapper, rateLimitService, null);
    }

    @Autowired
    public GatewayService(RouteRegistry routeRegistry, ObjectMapper objectMapper,
                          RateLimitService rateLimitService,
                          IdempotencyService idempotencyService) {
        this.routeRegistry = routeRegistry;
        this.objectMapper = objectMapper;
        this.rateLimitService = rateLimitService;
        this.idempotencyService = idempotencyService;
    }

    public ResponseEntity<byte[]> handle(HttpServletRequest request) {
        String correlationId = correlationId(request);

        Optional<CompiledRoute> matchedRoute = routeRegistry.find(request.getRequestURI());
        if(matchedRoute.isEmpty()) {
            return error(HttpStatus.NOT_FOUND, "No active route matches this request",
                    correlationId, null);
        }

        CompiledRoute compiledRoute = matchedRoute.get();
        GatewayRoute route = compiledRoute.getGatewayRoute();

        if(route.getAllowedMethods() == null || route.getAllowedMethods().isEmpty() ||
                route.getAllowedMethods().stream()
                        .noneMatch(method -> method.equalsIgnoreCase(request.getMethod()))) {
            HttpHeaders headers = new HttpHeaders();
            if(route.getAllowedMethods() != null) {
                headers.set(HttpHeaders.ALLOW, String.join(", ", route.getAllowedMethods()));
            }
            return error(HttpStatus.METHOD_NOT_ALLOWED, "HTTP method is not allowed for this route",
                    correlationId, headers);
        }

        RateLimitResult rateLimit;
        try {
            rateLimit = rateLimitService.check(route, request.getRemoteAddr());
        } catch (RuntimeException exception) {
            HttpStatus status = exception instanceof in.subhadeep.gatekeeper.gatekeeper.gateway.exception.GatewayPolicyException policy
                    ? policy.getStatus() : HttpStatus.SERVICE_UNAVAILABLE;
            return error(status, "Rate limiting is temporarily unavailable",
                    correlationId, null);
        }

        HttpHeaders policyHeaders = rateLimitHeaders(rateLimit);
        if(!rateLimit.isAllowed()) {
            policyHeaders.set(HttpHeaders.RETRY_AFTER,
                    String.valueOf(rateLimit.getRetryAfterSeconds()));
            return error(HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded",
                    correlationId, policyHeaders);
        }

        String idempotencyKey = request.getHeader("Idempotency-Key");
        boolean idempotentPost = route.isIdempotencyEnabled() && "POST".equalsIgnoreCase(request.getMethod());
        if(idempotentPost && (idempotencyKey == null || idempotencyKey.isBlank())) {
            return error(HttpStatus.BAD_REQUEST, "Idempotency-Key header is required",
                    correlationId, policyHeaders);
        }
        if(idempotentPost && idempotencyService != null) {
            Optional<IdempotencyService.Replay> replay = idempotencyService.replay(
                    String.valueOf(route.getOrganizationId()), route.getRouteId(), idempotencyKey);
            if(replay.isPresent()) {
                IdempotencyService.Replay saved = replay.get();
                HttpHeaders headers = new HttpHeaders();
                if(saved.contentType() != null && !saved.contentType().isBlank()) {
                    headers.set(HttpHeaders.CONTENT_TYPE, saved.contentType());
                }
                headers.set("X-Correlation-ID", correlationId);
                policyHeaders.forEach((name, values) -> headers.put(name, values));
                headers.set("Idempotency-Replayed", "true");
                return ResponseEntity.status(saved.status()).headers(headers).body(saved.body());
            }
        }

        try {
            URI targetUri = targetUri(compiledRoute, request);
            HttpRequest.Builder builder = HttpRequest.newBuilder(targetUri)
                    .method(request.getMethod(), bodyPublisher(request));

            if(route.getResponseTimeoutMs() != null && route.getResponseTimeoutMs() > 0) {
                builder.timeout(Duration.ofMillis(route.getResponseTimeoutMs()));
            }

            copyRequestHeaders(request, builder, route);
            applyRequestHeaderRules(builder, route);

            HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
            HttpResponse<byte[]> response = client.send(builder.build(),
                    HttpResponse.BodyHandlers.ofByteArray());

            HttpHeaders headers = copyResponseHeaders(response);
            applyResponseHeaderRules(headers, route);
            policyHeaders.forEach((name, values) -> headers.put(name, values));
            headers.set("X-Correlation-ID", correlationId);

            if(idempotentPost && idempotencyService != null) {
                idempotencyService.save(String.valueOf(route.getOrganizationId()), route.getRouteId(),
                        idempotencyKey, response.statusCode(), response.body(),
                        response.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElse(null));
            }

            return ResponseEntity.status(response.statusCode()).headers(headers).body(response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return error(HttpStatus.BAD_GATEWAY, "The upstream request was interrupted",
                    correlationId, policyHeaders);
        } catch (Exception exception) {
            return error(HttpStatus.BAD_GATEWAY, "Unable to reach the upstream service",
                    correlationId, policyHeaders);
        }
    }

    private URI targetUri(CompiledRoute compiledRoute, HttpServletRequest request) {
        GatewayRoute route = compiledRoute.getGatewayRoute();
        String requestUri = request.getRequestURI();
        String suffix = "";

        String sourcePrefix = compiledRoute.getSourcePathPrefix();
        if(sourcePrefix != null && requestUri.startsWith(sourcePrefix)) {
            suffix = requestUri.substring(sourcePrefix.length());
        }

        String targetPrefix = route.getTargetPathPrefix();
        if(targetPrefix == null) {
            targetPrefix = "";
        }

        String path = targetPrefix + suffix;
        if(!path.startsWith("/")) {
            path = "/" + path;
        }

        String query = request.getQueryString();
        if(query != null && !query.isBlank()) {
            path += "?" + query;
        }

        return URI.create(route.getTargetBaseUrl().replaceAll("/$", "") + path);
    }

    private HttpRequest.BodyPublisher bodyPublisher(HttpServletRequest request) throws Exception {
        if(request.getContentLengthLong() == 0 && request.getInputStream().available() == 0) {
            return HttpRequest.BodyPublishers.noBody();
        }
        byte[] body = request.getInputStream().readAllBytes();
        return HttpRequest.BodyPublishers.ofByteArray(body);
    }

    private void copyRequestHeaders(HttpServletRequest request,
                                    HttpRequest.Builder builder,
                                    GatewayRoute route) {
        Enumeration<String> names = request.getHeaderNames();
        while(names != null && names.hasMoreElements()) {
            String name = names.nextElement();
            if(isHopByHopHeader(name) || name.equalsIgnoreCase(HttpHeaders.HOST) ||
                    name.equalsIgnoreCase(HttpHeaders.CONTENT_LENGTH)) {
                continue;
            }
            Enumeration<String> values = request.getHeaders(name);
            while(values.hasMoreElements()) {
                builder.header(name, values.nextElement());
            }
        }
        builder.setHeader(HttpHeaders.CONTENT_LENGTH,
                String.valueOf(request.getContentLengthLong() < 0 ? 0 : request.getContentLengthLong()));
    }

    private void applyRequestHeaderRules(HttpRequest.Builder builder, GatewayRoute route) {
        if(route.getHeaderRules() == null) {
            return;
        }
        for(in.subhadeep.gatekeeper.gatekeeper.gateway.dto.HeaderRule rule : route.getHeaderRules()) {
            if(!"REQUEST".equalsIgnoreCase(rule.getDirection())) {
                continue;
            }
            applyHeaderRule(builder, null, rule);
        }
    }

    private void applyHeaderRule(HttpRequest.Builder requestBuilder,
                                 HttpHeaders responseHeaders,
                                 in.subhadeep.gatekeeper.gatekeeper.gateway.dto.HeaderRule rule) {
        if(rule.getHeaderName() == null || rule.getHeaderName().isBlank()) {
            return;
        }
        String action = rule.getAction() == null ? "" : rule.getAction().toUpperCase();
        if("REMOVE".equals(action)) {
            if(requestBuilder != null) {
                // JDK HttpRequest.Builder has no removal API, so omission is applied upstream.
            } else {
                responseHeaders.remove(rule.getHeaderName());
            }
        } else if("SET".equals(action) || "ADD".equals(action)) {
            if(requestBuilder != null) {
                if("SET".equals(action)) {
                    requestBuilder.setHeader(rule.getHeaderName(), rule.getHeaderValue());
                } else {
                    requestBuilder.header(rule.getHeaderName(), rule.getHeaderValue());
                }
            } else if("SET".equals(action)) {
                responseHeaders.set(rule.getHeaderName(), rule.getHeaderValue());
            } else {
                responseHeaders.add(rule.getHeaderName(), rule.getHeaderValue());
            }
        }
    }

    private HttpHeaders copyResponseHeaders(HttpResponse<byte[]> response) {
        HttpHeaders headers = new HttpHeaders();
        response.headers().map().forEach((name, values) -> {
            if(!isHopByHopHeader(name)) {
                values.forEach(value -> headers.add(name, value));
            }
        });
        return headers;
    }

    private void applyResponseHeaderRules(HttpHeaders headers, GatewayRoute route) {
        if(route.getHeaderRules() == null) {
            return;
        }
        for(in.subhadeep.gatekeeper.gatekeeper.gateway.dto.HeaderRule rule : route.getHeaderRules()) {
            if("RESPONSE".equalsIgnoreCase(rule.getDirection())) {
                applyHeaderRule(null, headers, rule);
            }
        }
    }

    private boolean isHopByHopHeader(String name) {
        return name.equalsIgnoreCase("Connection") || name.equalsIgnoreCase("Keep-Alive") ||
                name.equalsIgnoreCase("Proxy-Authenticate") || name.equalsIgnoreCase("Proxy-Authorization") ||
                name.equalsIgnoreCase("TE") || name.equalsIgnoreCase("Trailer") ||
                name.equalsIgnoreCase("Transfer-Encoding") || name.equalsIgnoreCase("Upgrade") ||
                name.equalsIgnoreCase("Content-Length");
    }

    private String correlationId(HttpServletRequest request) {
        String correlationId = request.getHeader("X-Correlation-ID");

        if(correlationId == null || correlationId.trim().isEmpty()) {
            return UUID.randomUUID().toString();
        }
        return correlationId;
    }

    private HttpHeaders rateLimitHeaders(RateLimitResult result) {
        HttpHeaders headers = new HttpHeaders();
        if(result.getLimit() > 0) {
            headers.set("X-RateLimit-Limit", String.valueOf(result.getLimit()));
            headers.set("X-RateLimit-Remaining", String.valueOf(result.getRemaining()));
        }
        return headers;
    }

    private ResponseEntity<byte[]> error(HttpStatus status,
                                         String message,
                                         String correlationId,
                                         HttpHeaders additionalHeaders) {

        GatewayErrorResponse response = new GatewayErrorResponse(status.value(), message,
                correlationId, LocalDateTime.now());

        HttpHeaders headers = additionalHeaders == null ? new HttpHeaders() : additionalHeaders;

        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Correlation-ID", correlationId);

        return ResponseEntity.status(status).headers(headers)
                .body(objectMapper.writeValueAsString(response).getBytes(StandardCharsets.UTF_8));
    }
}