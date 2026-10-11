package com.zerotrust.gateway.ratelimit;

import com.zerotrust.gateway.metrics.SecurityMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// 필터만 따로 떼어 "어느 카운터를 어떤 순서로 세는가"를 확인한다.
// 진짜 서버를 띄우는 통합 테스트는 보내는 쪽 IP가 하나뿐이라, IP가 다른 두 사람이 필요한 상황은 여기서만 만들 수 있다.
class LoginRateLimitFilterTest {

    private static final String ATTACKER_IP = "203.0.113.10";
    private static final String OWNER_IP = "198.51.100.20";

    // Redis 대신 메모리에 세는 가짜. 세는 순서만 보면 되므로 만료는 흉내 내지 않는다.
    private static class InMemoryRateLimiter extends RateLimiter {
        private final Map<String, Long> counts = new HashMap<>();

        InMemoryRateLimiter() {
            super(null);
        }

        @Override
        public Hit hit(String key, Duration window) {
            return new Hit(counts.merge(key, 1L, Long::sum), window.toSeconds());
        }

        @Override
        public void reset(List<String> keys) {
            keys.forEach(counts::remove);
        }
    }

    private final InMemoryRateLimiter limiter = new InMemoryRateLimiter();

    // 1분에 IP 30회, 이메일+IP 5회, 이메일 20회. 운영 설정과 같은 값.
    private final LoginRateLimitFilter filter = new LoginRateLimitFilter(
            limiter, new SecurityMetrics(new SimpleMeterRegistry()), 60, 30, 5, 20);

    private MockHttpServletRequest lastRequest;

    // 필터를 통과하면 가짜 응답의 기본값인 200, 막히면 필터가 쓴 429가 나온다.
    private int login(String ip, String email) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/login");
        request.setRemoteAddr(ip);
        request.setContent(("{\"email\":\"" + email + "\",\"password\":\"wrong\"}").getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        lastRequest = request;
        return response.getStatus();
    }

    @Test
    @DisplayName("한 IP가 이메일을 매번 바꿔 보내도 31번째부터 429이고, 다른 IP는 영향이 없다")
    void sameIpWithDifferentEmailsIsBlocked() throws Exception {
        for (int i = 1; i <= 30; i++) {
            assertThat(login(ATTACKER_IP, "user" + i + "@example.com")).as("%d번째 시도", i).isEqualTo(200);
        }

        assertThat(login(ATTACKER_IP, "user31@example.com")).isEqualTo(429);
        assertThat(login(OWNER_IP, "someone@example.com")).isEqualTo(200);
    }

    @Test
    @DisplayName("공격자가 IP 하나로 남의 이메일에 100번 틀려도, 주인은 자기 IP에서 시도할 수 있다")
    void blockedAttemptsDoNotLockTheOwner() throws Exception {
        String victim = "victim@example.com";

        for (int i = 1; i <= 100; i++) {
            login(ATTACKER_IP, victim);
        }

        // 통과한 5번만 이메일 카운터에 쌓였다. 막힌 95번은 세지 않았다.
        assertThat(limiter.counts.get("rl:login:email:" + victim)).isEqualTo(5);
        assertThat(login(OWNER_IP, victim)).isEqualTo(200);
    }

    @Test
    @DisplayName("로그인 성공 때 지울 카운터 목록에 IP 카운터는 들어가지 않는다")
    void ipCounterIsNotInResetKeys() throws Exception {
        login(OWNER_IP, "owner@example.com");

        @SuppressWarnings("unchecked")
        List<String> keys = (List<String>) lastRequest.getAttribute(LoginRateLimitFilter.KEYS_ATTRIBUTE);

        assertThat(keys).hasSize(2).noneMatch(key -> key.startsWith("rl:login:ip:"));
    }
}
