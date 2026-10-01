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

// 통합 테스트의 공통 토대: 진짜 PostgreSQL을 Docker로 띄우고, 앱 전체를 임의의 포트에 실제로 기동한다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTestSupport {

    // 모든 테스트 클래스가 컨테이너 하나를 같이 쓴다. 클래스마다 새로 띄우면 그만큼 느려진다.
    // 테스트가 끝나 프로그램이 종료되면 Testcontainers가 컨테이너를 알아서 지운다.
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    static {
        POSTGRES.start();
    }

    // 앱이 뜨기 전에 설정값을 바꿔치기한다. 개발용 DB(.env) 대신 방금 띄운 컨테이너에 접속하게 된다.
    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // .env가 없는 환경(CI 등)에서도 돌도록 테스트 전용 키를 넣는다. 실제 키가 아니다.
        registry.add("jwt.secret", () -> "test-only-secret-key-for-integration-tests-0123456789");
        // 유예 시간이 지난 경우를 테스트하려면 기다려야 하므로, 테스트에서는 1초로 줄인다.
        registry.add("refresh.reuse-grace-seconds", () -> "1");
    }

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
}
