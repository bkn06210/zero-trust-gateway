package com.zerotrust.gateway.proxy;

import com.zerotrust.gateway.auth.AuthenticatedUser;
import com.zerotrust.gateway.auth.JwtAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

// Filter를 통과한 모든 요청을 받아 뒤쪽 서비스로 그대로 전달하고, 받은 응답을 그대로 돌려준다.
@RestController
public class ProxyController {

    private static final Logger log = LoggerFactory.getLogger(ProxyController.class);

    // 클라이언트 요청에서 뒤로 넘겨줄 헤더. 이것만 넘기고 나머지는 전부 버린다 (허용 목록).
    // X-User-* 는 여기 없으므로, 클라이언트가 직접 써서 보낸 것은 자동으로 떨어져 나간다.
    private static final List<String> FORWARDED_HEADERS = List.of("Content-Type", "Accept");

    // 경로 앞부분 → 어느 서비스로 보낼지.
    private final Map<String, String> routes;
    private final RestClient restClient;

    public ProxyController(@Value("${services.auth.url}") String authServiceUrl) {
        this.routes = Map.of(
                "/auth/", authServiceUrl,
                "/users/", authServiceUrl
        );

        // 뒤쪽 서비스가 느리거나 죽었을 때 Gateway까지 같이 멈추지 않도록 시간 제한을 건다.
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    @RequestMapping("/**")
    public ResponseEntity<byte[]> proxy(HttpServletRequest request, @RequestBody(required = false) byte[] body) {
        String path = request.getRequestURI();

        // 1. 수상한 경로는 넘기지 않는다. Gateway와 뒤쪽 서비스가 경로를 서로 다르게 해석하면 검사를 피해갈 수 있다.
        if (isSuspicious(path)) {
            return json(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "잘못된 경로입니다.");
        }

        // 2. 어느 서비스로 보낼지 정한다. 등록되지 않은 경로는 404.
        String target = routes.entrySet().stream()
                .filter(route -> path.startsWith(route.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
        if (target == null) {
            return json(HttpStatus.NOT_FOUND, "NOT_FOUND", "존재하지 않는 경로입니다.");
        }

        String query = request.getQueryString();
        URI uri = URI.create(target + path + (query != null ? "?" + query : ""));

        // 3. Filter가 검증해서 붙여둔 신원. 공개 경로면 null.
        AuthenticatedUser user = (AuthenticatedUser) request.getAttribute(JwtAuthFilter.USER_ATTRIBUTE);

        try {
            RestClient.RequestBodySpec outgoing = restClient
                    .method(HttpMethod.valueOf(request.getMethod()))
                    .uri(uri)
                    .headers(headers -> {
                        // 3-1. 허용한 헤더만 복사한다. Authorization(토큰)도 넘기지 않는다 — 뒤쪽은 토큰이 필요 없다.
                        for (String name : FORWARDED_HEADERS) {
                            String value = request.getHeader(name);
                            if (value != null) {
                                headers.set(name, value);
                            }
                        }
                        // 3-2. 신원 헤더는 Gateway가 검증한 값으로만 쓴다.
                        if (user != null) {
                            headers.set("X-User-Id", user.userId());
                            headers.set("X-User-Role", user.role());
                        }
                    });
            if (body != null && body.length > 0) {
                outgoing.body(body);
            }

            // 4. 뒤쪽의 응답(상태 코드, 본문)을 손대지 않고 그대로 돌려준다. 4xx, 5xx도 그대로.
            return outgoing.exchange((req, res) -> {
                ResponseEntity.BodyBuilder builder = ResponseEntity.status(res.getStatusCode());
                MediaType contentType = res.getHeaders().getContentType();
                if (contentType != null) {
                    builder.contentType(contentType);
                }
                return builder.body(res.getBody().readAllBytes());
            });
        } catch (ResourceAccessException e) {
            // 5. 뒤쪽 서비스에 연결 자체가 안 됨(죽었거나 시간 초과). 내부 주소는 응답에 싣지 않는다.
            log.error("뒤쪽 서비스 연결 실패: {} {}", request.getMethod(), uri, e);
            return json(HttpStatus.BAD_GATEWAY, "BAD_GATEWAY", "서비스에 연결할 수 없습니다.");
        }
    }

    private boolean isSuspicious(String path) {
        String lower = path.toLowerCase();
        return lower.contains("..") || lower.contains("//") || lower.contains(";")
                || lower.contains("\\") || lower.contains("%");
    }

    private ResponseEntity<byte[]> json(HttpStatus status, String code, String message) {
        String body = "{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}";
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body.getBytes(StandardCharsets.UTF_8));
    }
}
