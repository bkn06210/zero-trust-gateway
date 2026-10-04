package com.zerotrust.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitTest extends GatewayTestSupport {

    private String newEmail() {
        return "rl-" + UUID.randomUUID() + "@example.com";
    }

    private HttpResponse<String> login(String email, String password) throws Exception {
        return send("POST", "/auth/login", "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
    }

    @Test
    @DisplayName("같은 이메일로 5번 실패하면 6번째부터 429이고, 뒤쪽 서비스에는 더 이상 가지 않는다")
    void sixthAttemptIsBlocked() throws Exception {
        String email = newEmail();

        for (int i = 1; i <= 5; i++) {
            assertThat(login(email, "wrong-" + i).statusCode()).as("%d번째 시도", i).isEqualTo(401);
        }
        assertThat(BACKEND_CALLS.get()).isEqualTo(5);

        HttpResponse<String> blocked = login(email, "wrong-6");

        assertThat(blocked.statusCode()).isEqualTo(429);
        assertThat(blocked.headers().firstValue("Retry-After")).isPresent();
        assertThat(blocked.body()).contains("TOO_MANY_REQUESTS");
        assertThat(BACKEND_CALLS.get()).as("차단된 요청은 뒤로 전달되지 않는다").isEqualTo(5);
    }

    @Test
    @DisplayName("한도를 넘은 뒤에는 맞는 비밀번호여도 429다 (무차별 대입을 막는 핵심)")
    void correctPasswordIsAlsoBlockedAfterLimit() throws Exception {
        String email = newEmail();
        for (int i = 1; i <= 5; i++) {
            login(email, "wrong-" + i);
        }

        assertThat(login(email, CORRECT_PASSWORD).statusCode()).isEqualTo(429);
    }

    @Test
    @DisplayName("다른 이메일의 시도는 서로 영향을 주지 않는다")
    void differentEmailsAreCountedSeparately() throws Exception {
        String victim = newEmail();
        for (int i = 1; i <= 5; i++) {
            login(victim, "wrong-" + i);
        }

        assertThat(login(newEmail(), "wrong").statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("대소문자만 다른 이메일은 같은 계정으로 세어 한도를 피해 갈 수 없다")
    void emailCaseVariationSharesTheCounter() throws Exception {
        String email = newEmail();
        for (int i = 1; i <= 5; i++) {
            login(i % 2 == 0 ? email.toUpperCase() : email, "wrong-" + i);
        }

        assertThat(login(email.toUpperCase(), "wrong").statusCode()).isEqualTo(429);
    }

    @Test
    @DisplayName("로그인에 성공하면 실패 횟수가 지워진다")
    void successfulLoginResetsCounter() throws Exception {
        String email = newEmail();
        for (int i = 1; i <= 4; i++) {
            login(email, "wrong-" + i);
        }

        assertThat(login(email, CORRECT_PASSWORD).statusCode()).isEqualTo(200);

        // 지워졌으니 다시 5번까지 가능하다
        for (int i = 1; i <= 5; i++) {
            assertThat(login(email, "wrong-again-" + i).statusCode()).as("성공 후 %d번째 시도", i).isEqualTo(401);
        }
        assertThat(login(email, "wrong-again-6").statusCode()).isEqualTo(429);
    }

    @Test
    @DisplayName("필터가 본문을 읽어도 뒤쪽 서비스는 본문을 온전히 받는다")
    void bodyIsStillForwardedAfterFilterReadsIt() throws Exception {
        String email = newEmail();

        login(email, "some-password");

        assertThat(LAST_BODY.get()).contains(email).contains("some-password");
    }

    @Test
    @DisplayName("이메일이 없는 본문은 세지 않고 그대로 넘긴다")
    void bodyWithoutEmailPassesThrough() throws Exception {
        assertThat(send("POST", "/auth/login", "{\"password\":\"x\"}").statusCode()).isEqualTo(401);
        assertThat(BACKEND_CALLS.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("지나치게 큰 로그인 본문은 413으로 거절한다")
    void oversizedBodyIsRejected() throws Exception {
        String huge = "{\"email\":\"" + newEmail() + "\",\"password\":\"" + "a".repeat(10_000) + "\"}";

        assertThat(send("POST", "/auth/login", huge).statusCode()).isEqualTo(413);
        assertThat(BACKEND_CALLS.get()).isZero();
    }
}
