package com.zerotrust.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

// 통합 테스트의 공통 토대: 진짜 PostgreSQL에 붙이고, 앱 전체를 임의의 포트에 실제로 기동한다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTestSupport {

    // PostgreSQL을 어디서 구하나.
    // - TEST_DB_URL이 주어지면 이미 떠 있는 것에 붙는다. CI가 이 길이다. 러너가 작업 옆에 띄워 준다.
    // - 없으면 여기서 직접 띄운다. PC가 이 길이다. Docker만 켜져 있으면 준비 없이 돌릴 수 있다.
    // 길을 나눈 이유: 직접 띄우려면 Docker 데몬이 필요한데, CI 작업은 컨테이너 안에서 돌아서
    // 데몬을 쓰려면 특권 모드로 하나를 더 띄워야 한다. 테스트 때문에 그 권한을 요구하지 않으려고 CI에서는 붙기만 한다.
    private static final String EXTERNAL_DB_URL = System.getenv("TEST_DB_URL");

    // 모든 테스트 클래스가 컨테이너 하나를 같이 쓴다. 클래스마다 새로 띄우면 그만큼 느려진다.
    // 테스트가 끝나 프로그램이 종료되면 Testcontainers가 컨테이너를 알아서 지운다.
    private static final PostgreSQLContainer POSTGRES =
            EXTERNAL_DB_URL == null ? new PostgreSQLContainer("postgres:16") : null;

    static {
        if (POSTGRES != null) {
            POSTGRES.start();
        }
    }

    // 앱이 뜨기 전에 설정값을 바꿔치기한다. 개발용 DB(.env) 대신 테스트용 DB에 접속하게 된다.
    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        if (POSTGRES != null) {
            registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
            registry.add("spring.datasource.username", POSTGRES::getUsername);
            registry.add("spring.datasource.password", POSTGRES::getPassword);
        } else {
            registry.add("spring.datasource.url", () -> EXTERNAL_DB_URL);
            registry.add("spring.datasource.username", () -> System.getenv("TEST_DB_USER"));
            registry.add("spring.datasource.password", () -> System.getenv("TEST_DB_PASSWORD"));
        }
        // .env가 없는 환경(CI 등)에서도 돌도록 테스트 전용 키를 넣는다. 실제 키가 아니다.
        registry.add("jwt.secret", () -> "test-only-secret-key-for-integration-tests-0123456789");
        // 유예 시간이 지난 경우를 테스트하려면 기다려야 하므로, 테스트에서는 1초로 줄인다.
        registry.add("refresh.reuse-grace-seconds", () -> "1");
        // 동시 가입 50건 같은 테스트는 BCrypt 자리를 1초 넘게 기다리는 요청이 생긴다. 여기서 보려는 건 과부하 거절이 아니라
        // 동시성이므로, 503이 섞이지 않게 대기 시간을 넉넉히 준다. 거절 동작은 LimitedPasswordEncoderTest가 따로 확인한다.
        registry.add("bcrypt.max-wait-ms", () -> "30000");
        registry.add("admin.email", () -> ADMIN_EMAIL);
        registry.add("admin.password", () -> ADMIN_PASSWORD);
    }

    protected static final String ADMIN_EMAIL = "admin@test.example.com";
    protected static final String ADMIN_PASSWORD = "admin-test-password";

    @Value("${local.server.port}")
    private int port;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    protected record Response(int status, Map<String, Object> body) {
        public String get(String field) {
            return String.valueOf(body.get(field));
        }
    }

    protected Response post(String path, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        Map<String, Object> body = response.body().isBlank()
                ? Map.of()
                : JsonParserFactory.getJsonParser().parseMap(response.body());
        return new Response(response.statusCode(), body);
    }

    // 응답 본문이 목록일 수도 있어 상태 코드만 돌려준다. headers는 이름, 값, 이름, 값 순서.
    protected int getStatus(String path, String... headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        for (int i = 0; i < headers.length; i += 2) {
            builder.header(headers[i], headers[i + 1]);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }
}
