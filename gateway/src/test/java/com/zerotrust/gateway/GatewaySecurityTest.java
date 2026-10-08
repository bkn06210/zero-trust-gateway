package com.zerotrust.gateway;

import com.sun.net.httpserver.Headers;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class GatewaySecurityTest extends GatewayTestSupport {

    // ---- 차단되어야 하는 것들 ----

    @Test
    @DisplayName("토큰이 없으면 401이고, 뒤쪽 서비스는 호출조차 되지 않는다")
    void noToken() throws Exception {
        assertThat(call("GET", "/users/me")).isEqualTo(401);
        assertThat(BACKEND_CALLS.get()).isZero();
    }

    @Test
    @DisplayName("payload의 role을 ADMIN으로 바꾼 위조 토큰은 401")
    void tamperedPayload() throws Exception {
        String[] parts = validToken().split("\\.");
        String forgedPayload = base64Url("{\"sub\":\"1\",\"role\":\"ADMIN\",\"exp\":9999999999}");

        assertThat(getWithToken(parts[0] + "." + forgedPayload + "." + parts[2])).isEqualTo(401);
        assertThat(BACKEND_CALLS.get()).isZero();
    }

    @Test
    @DisplayName("만료된 토큰은 서명이 맞아도 401")
    void expiredToken() throws Exception {
        String expired = token("1", "USER", Instant.now().minusSeconds(60), KEY);

        assertThat(getWithToken(expired)).isEqualTo(401);
        assertThat(BACKEND_CALLS.get()).isZero();
    }

    @Test
    @DisplayName("다른 비밀키로 서명한 토큰은 401")
    void signedWithAnotherKey() throws Exception {
        SecretKey attackerKey = Keys.hmacShaKeyFor("attacker-key-attacker-key-attacker-key-0123".getBytes(StandardCharsets.UTF_8));

        assertThat(getWithToken(token("1", "ADMIN", Instant.now().plusSeconds(600), attackerKey))).isEqualTo(401);
        assertThat(BACKEND_CALLS.get()).isZero();
    }

    @Test
    @DisplayName("서명을 비운 토큰(alg=none)은 401")
    void unsignedToken() throws Exception {
        String unsigned = base64Url("{\"alg\":\"none\"}") + "."
                + base64Url("{\"sub\":\"1\",\"role\":\"ADMIN\",\"exp\":9999999999}") + ".";

        assertThat(getWithToken(unsigned)).isEqualTo(401);
        assertThat(BACKEND_CALLS.get()).isZero();
    }

    @Test
    @DisplayName("공개 경로와 주소는 같아도 메서드가 다르면 토큰을 요구한다")
    void publicPathWithDifferentMethod() throws Exception {
        assertThat(call("GET", "/auth/login")).isEqualTo(401);
        assertThat(BACKEND_CALLS.get()).isZero();
    }

    // ---- 권한 (P2) ----

    @Test
    @DisplayName("USER 토큰으로 관리자 경로에 가면 403이고, 뒤쪽 서비스는 호출되지 않는다")
    void userCannotReachAdminPath() throws Exception {
        int status = call("GET", "/admin/users", "Authorization", "Bearer " + validToken());

        assertThat(status).isEqualTo(403);
        assertThat(BACKEND_CALLS.get()).isZero();
    }

    @Test
    @DisplayName("ADMIN 토큰이면 관리자 경로가 열리고, 역할이 뒤쪽으로 전달된다")
    void adminCanReachAdminPath() throws Exception {
        String adminToken = token("7", "ADMIN", Instant.now().plusSeconds(600), KEY);

        assertThat(call("GET", "/admin/users", "Authorization", "Bearer " + adminToken)).isEqualTo(200);
        assertThat(LAST_HEADERS.get().getFirst("X-User-Role")).isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("토큰 없이 관리자 경로에 가면 403이 아니라 401이다")
    void adminPathWithoutTokenIsUnauthorized() throws Exception {
        assertThat(call("GET", "/admin/users")).isEqualTo(401);
    }

    @Test
    @DisplayName("대소문자를 바꾼 관리자 경로로도 USER는 통과하지 못한다")
    void adminPathCaseVariation() throws Exception {
        int status = call("GET", "/ADMIN/users", "Authorization", "Bearer " + validToken());

        assertThat(status).isEqualTo(403);
        assertThat(BACKEND_CALLS.get()).isZero();
    }

    @Test
    @DisplayName("USER 토큰에 X-User-Role: ADMIN 헤더를 직접 써도 403이다")
    void roleHeaderCannotGrantAdmin() throws Exception {
        int status = call("GET", "/admin/users",
                "Authorization", "Bearer " + validToken(),
                "X-User-Role", "ADMIN");

        assertThat(status).isEqualTo(403);
        assertThat(BACKEND_CALLS.get()).isZero();
    }

    // ---- 통과되어야 하는 것들 ----

    @Test
    @DisplayName("정상 토큰이면 뒤쪽으로 전달되고, 검증된 신원이 헤더로 붙는다")
    void validTokenIsForwardedWithIdentity() throws Exception {
        assertThat(getWithToken(validToken())).isEqualTo(200);

        Headers received = LAST_HEADERS.get();
        assertThat(received.getFirst("X-User-Id")).isEqualTo("1");
        assertThat(received.getFirst("X-User-Role")).isEqualTo("USER");
    }

    @Test
    @DisplayName("클라이언트가 직접 쓴 신원 헤더는 버려지고, 토큰은 뒤쪽으로 넘어가지 않는다")
    void clientSuppliedIdentityIsDiscarded() throws Exception {
        int status = call("GET", "/users/me",
                "Authorization", "Bearer " + validToken(),
                "X-User-Id", "999",
                "X-User-Role", "ADMIN");

        assertThat(status).isEqualTo(200);
        Headers received = LAST_HEADERS.get();
        assertThat(received.get("X-User-Id")).containsExactly("1");
        assertThat(received.get("X-User-Role")).containsExactly("USER");
        assertThat(received.containsKey("Authorization")).isFalse();
    }

    @Test
    @DisplayName("공개 경로는 토큰 없이 전달되고, 신원 헤더는 붙지 않는다")
    void publicEndpointNeedsNoToken() throws Exception {
        assertThat(call("POST", "/auth/signup", "X-User-Id", "999")).isEqualTo(200);

        assertThat(BACKEND_CALLS.get()).isEqualTo(1);
        assertThat(LAST_HEADERS.get().containsKey("X-User-Id")).isFalse();
    }

    // ---- 지표 ----

    @Test
    @DisplayName("막은 요청은 종류별로 세어져 지표로 노출되고, 지표 포트는 토큰 없이 읽힌다")
    void blockedRequestsAreCounted() throws Exception {
        call("GET", "/users/me");
        call("GET", "/admin/users", "Authorization", "Bearer " + validToken());

        String metrics = metrics();
        assertThat(metrics).contains("gateway_blocked_requests_total{outcome=\"unauthorized\",reason=\"missing_token\"}");
        assertThat(metrics).contains("gateway_blocked_requests_total{outcome=\"forbidden\",reason=\"role\"}");
    }
}
