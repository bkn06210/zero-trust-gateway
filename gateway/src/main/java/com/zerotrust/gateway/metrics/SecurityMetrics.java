package com.zerotrust.gateway.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

// Gateway가 막은 요청을 종류별로 센다. 대시보드의 "지금 공격이 얼마나 막히고 있나"가 이 숫자에서 나온다.
// 태그에 경로나 IP는 넣지 않는다. 값의 종류가 무한히 늘어나면 지표 저장소가 터진다(카디널리티 문제).
@Component
public class SecurityMetrics {

    private final MeterRegistry registry;

    public SecurityMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    // 인증 실패. reason: missing_token, invalid_token
    public void unauthorized(String reason) {
        Counter.builder("gateway_blocked_requests_total")
                .description("Gateway가 거부한 요청 수")
                .tag("outcome", "unauthorized")
                .tag("reason", reason)
                .register(registry)
                .increment();
    }

    // 권한 없음
    public void forbidden() {
        Counter.builder("gateway_blocked_requests_total")
                .description("Gateway가 거부한 요청 수")
                .tag("outcome", "forbidden")
                .tag("reason", "role")
                .register(registry)
                .increment();
    }

    // 로그인 시도. result: allowed, rate_limited
    public void loginAttempt(String result) {
        Counter.builder("gateway_login_attempts_total")
                .description("로그인 시도 수")
                .tag("result", result)
                .register(registry)
                .increment();
    }
}
