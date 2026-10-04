package com.zerotrust.gateway;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

// Gateway 테스트의 공통 토대.
// - 뒤쪽 서비스 자리에는 "받은 요청을 기록만 하는 가짜 서버"를 둔다 → 무엇을 넘겼는지, 넘기긴 했는지 확인 가능
// - 요청 제한에 쓰는 Redis는 Docker로 진짜를 띄운다
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class GatewayTestSupport {

    protected static final String SECRET = "test-only-secret-key-for-gateway-tests-0123456789";
    protected static final SecretKey KEY = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

    // 가짜 뒤쪽 서버: 로그인은 비밀번호가 "correct-password"일 때만 200, 아니면 401. 나머지 경로는 200.
    protected static final String CORRECT_PASSWORD = "correct-password";
    protected static final HttpServer BACKEND;
    protected static final AtomicInteger BACKEND_CALLS = new AtomicInteger();
    protected static final AtomicReference<Headers> LAST_HEADERS = new AtomicReference<>();
    protected static final AtomicReference<String> LAST_BODY = new AtomicReference<>();

    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static {
        REDIS.start();
        try {
            BACKEND = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        BACKEND.createContext("/", exchange -> {
            BACKEND_CALLS.incrementAndGet();
            LAST_HEADERS.set(exchange.getRequestHeaders());
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            LAST_BODY.set(body);

            boolean login = exchange.getRequestURI().getPath().equals("/auth/login");
            int status = (login && !body.contains(CORRECT_PASSWORD)) ? 401 : 200;
            byte[] response = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        BACKEND.start();
    }

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("jwt.secret", () -> SECRET);
        registry.add("services.auth.url", () -> "http://localhost:" + BACKEND.getAddress().getPort());
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Value("${local.server.port}")
    private int port;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @BeforeEach
    void resetBackend() {
        BACKEND_CALLS.set(0);
        LAST_HEADERS.set(null);
        LAST_BODY.set(null);
    }

    protected String token(String userId, String role, Instant expiresAt, SecretKey key) {
        return Jwts.builder()
                .subject(userId)
                .claim("role", role)
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();
    }

    protected String validToken() {
        return token("1", "USER", Instant.now().plusSeconds(600), KEY);
    }

    protected String base64Url(String text) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    protected HttpResponse<String> send(String method, String path, String body, String... headers) throws Exception {
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, publisher);
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        for (int i = 0; i < headers.length; i += 2) {
            builder.header(headers[i], headers[i + 1]);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    protected int call(String method, String path, String... headers) throws Exception {
        return send(method, path, null, headers).statusCode();
    }

    protected int getWithToken(String token) throws Exception {
        return call("GET", "/users/me", "Authorization", "Bearer " + token);
    }
}
