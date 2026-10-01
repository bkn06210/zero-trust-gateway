package com.zerotrust.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

// 같은 요청 여러 개를 "정확히 같은 순간"에 보냈을 때 무슨 일이 생기는지 확인한다.
class ConcurrencyTest extends IntegrationTestSupport {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // 요청 n개를 준비시켜 놓고 출발 신호 한 번에 동시에 내보낸다.
    private List<Response> fireSimultaneously(int n, Callable<Response> request) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Response>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return request.call();
                }));
            }
            ready.await();
            start.countDown();

            List<Response> responses = new ArrayList<>();
            for (Future<Response> future : futures) {
                responses.add(future.get());
            }
            return responses;
        } finally {
            pool.shutdownNow();
        }
    }

    private Map<Integer, Long> countByStatus(List<Response> responses) {
        Map<Integer, Long> counts = new TreeMap<>();
        responses.forEach(r -> counts.merge(r.status(), 1L, Long::sum));
        return counts;
    }

    private String credentials(String email) {
        return "{\"email\":\"" + email + "\",\"password\":\"password123\"}";
    }

    @Test
    @DisplayName("같은 이메일로 50명이 동시에 가입하면 정확히 한 명만 성공한다")
    void concurrentSignupWithSameEmail() throws Exception {
        String email = "race-" + UUID.randomUUID() + "@example.com";

        List<Response> responses = fireSimultaneously(50, () -> post("/auth/signup", credentials(email)));

        Map<Integer, Long> counts = countByStatus(responses);
        System.out.println("[실험] 동시 가입 50건 상태 코드 분포: " + counts);

        Integer rows = jdbcTemplate.queryForObject("SELECT count(*) FROM users WHERE email = ?", Integer.class, email);
        assertThat(rows).isEqualTo(1);
        assertThat(counts).containsOnlyKeys(201, 409);
        assertThat(counts.get(201)).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 refresh 토큰으로 20건이 동시에 갱신하면 한 건만 성공하고, 그 새 토큰은 살아 있다")
    void concurrentRefreshWithSameToken() throws Exception {
        String email = "race-" + UUID.randomUUID() + "@example.com";
        post("/auth/signup", credentials(email));
        String refreshToken = post("/auth/login", credentials(email)).get("refreshToken");
        String body = "{\"refreshToken\":\"" + refreshToken + "\"}";

        List<Response> responses = fireSimultaneously(20, () -> post("/auth/refresh", body));

        Map<Integer, Long> counts = countByStatus(responses);
        System.out.println("[실험] 동시 갱신 20건 상태 코드 분포: " + counts);

        // 성공한 한 건이 받은 새 토큰은 살아 있어야 한다. 동시에 온 나머지 요청이 그것까지 폐기하면 안 된다.
        Integer alive = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM refresh_tokens t JOIN users u ON t.user_id = u.id WHERE u.email = ? AND t.revoked = false",
                Integer.class, email);
        System.out.println("[실험] 동시 갱신 후 살아있는 refresh 토큰 수: " + alive);

        assertThat(counts).containsOnlyKeys(200, 401);
        assertThat(counts.get(200)).isEqualTo(1);
        assertThat(alive).isEqualTo(1);

        // 그 새 토큰으로 실제로 다시 갱신이 된다.
        String newToken = responses.stream().filter(r -> r.status() == 200).findFirst().orElseThrow().get("refreshToken");
        assertThat(post("/auth/refresh", "{\"refreshToken\":\"" + newToken + "\"}").status()).isEqualTo(200);
    }
}
