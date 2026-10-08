package com.zerotrust.gateway.ratelimit;

import com.zerotrust.gateway.metrics.SecurityMetrics;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

// 로그인 요청만 잡아서 "이 이메일(과 IP)로 1분에 몇 번째 시도인가"를 세고, 한도를 넘으면 429로 끊는다.
// 인증 필터보다 앞에 둔다. 로그인은 공개 경로라 인증 필터가 검사하지 않기 때문에, 막을 곳은 여기뿐이다.
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LoginRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(LoginRateLimitFilter.class);

    // 로그인 성공 시 카운터를 지울 수 있도록, 이 요청에 쓴 키들을 다음 단계에 알려주는 이름표.
    public static final String KEYS_ATTRIBUTE = "rateLimitKeys";

    // 로그인 본문은 이메일과 비밀번호뿐이다. 이보다 크면 정상 요청이 아니다.
    private static final int MAX_LOGIN_BODY_BYTES = 4 * 1024;

    private final RateLimiter rateLimiter;
    private final SecurityMetrics metrics;
    private final Duration window;
    private final int perEmailIp;
    private final int perEmail;

    public LoginRateLimitFilter(RateLimiter rateLimiter,
                                SecurityMetrics metrics,
                                @Value("${ratelimit.login.window-seconds}") long windowSeconds,
                                @Value("${ratelimit.login.per-email-ip}") int perEmailIp,
                                @Value("${ratelimit.login.per-email}") int perEmail) {
        this.rateLimiter = rateLimiter;
        this.metrics = metrics;
        this.window = Duration.ofSeconds(windowSeconds);
        this.perEmailIp = perEmailIp;
        this.perEmail = perEmail;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 로그인이 아니면 이 필터는 아무 일도 하지 않는다.
        return !("POST".equals(request.getMethod()) && "/auth/login".equals(request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // 1. 본문을 받아 둔다. 이 뒤로는 포장된 요청(wrapped)을 넘겨야 다음 단계가 본문을 다시 읽을 수 있다.
        RepeatableBodyRequest wrapped;
        try {
            wrapped = RepeatableBodyRequest.wrap(request, MAX_LOGIN_BODY_BYTES);
        } catch (RepeatableBodyRequest.BodyTooLargeException e) {
            writeError(response, 413, "PAYLOAD_TOO_LARGE", "요청 본문이 너무 큽니다.");
            return;
        }

        // 2. 이메일을 꺼낸다. 없거나 JSON이 아니면 세지 않고 넘긴다 — 뒤쪽이 400으로 싸게 거절한다 (BCrypt를 안 돈다).
        String email = extractEmail(wrapped.bodyAsString());
        if (email == null) {
            filterChain.doFilter(wrapped, response);
            return;
        }

        // 3. 두 가지 기준으로 센다. 이메일+IP는 한 공격자를, 이메일 단독은 IP를 바꿔 오는 공격을 막는다.
        String ip = request.getRemoteAddr();
        String emailIpKey = "rl:login:email-ip:" + email + ":" + ip;
        String emailKey = "rl:login:email:" + email;

        try {
            RateLimiter.Hit byEmailIp = rateLimiter.hit(emailIpKey, window);
            RateLimiter.Hit byEmail = rateLimiter.hit(emailKey, window);

            if (byEmailIp.count() > perEmailIp || byEmail.count() > perEmail) {
                long retryAfter = Math.max(byEmailIp.secondsUntilReset(), byEmail.secondsUntilReset());
                // 보안 이벤트. 이메일은 식별을 위해 남기되 비밀번호는 절대 남기지 않는다.
                log.warn("로그인 제한: email={} ip={} 시도={}회(email+ip) {}회(email)",
                        email, ip, byEmailIp.count(), byEmail.count());
                metrics.loginAttempt("rate_limited");
                response.setHeader("Retry-After", String.valueOf(Math.max(retryAfter, 1)));
                writeError(response, 429, "TOO_MANY_REQUESTS", "로그인 시도가 너무 많습니다. 잠시 후 다시 시도해주세요.");
                return;
            }
        } catch (DataAccessException e) {
            // 4. Redis에 접속할 수 없다. 제한 없이 통과시킨다(fail-open): 제한 장치가 죽었다고 로그인 전체를 막지는 않는다.
            //    대신 반드시 로그로 남겨 운영자가 알게 한다. 이 선택의 대가는 DECISIONS.md 참고.
            log.error("Redis 접속 실패 — 로그인 제한 없이 통과시킴", e);
        }

        // 5. 한도 안. 성공 시 카운터를 지울 수 있게 키를 붙여 두고 다음으로.
        metrics.loginAttempt("allowed");
        wrapped.setAttribute(KEYS_ATTRIBUTE, List.of(emailIpKey, emailKey));
        filterChain.doFilter(wrapped, response);
    }

    // 로그인 서비스와 같은 규칙으로 정규화한다. 안 하면 "Kim@"과 "kim@"이 따로 세어져 한도를 피해 간다.
    private String extractEmail(String json) {
        try {
            Map<String, Object> body = JsonParserFactory.getJsonParser().parseMap(json);
            Object email = body.get("email");
            if (email instanceof String s && !s.isBlank()) {
                return s.trim().toLowerCase();
            }
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void writeError(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }
}
