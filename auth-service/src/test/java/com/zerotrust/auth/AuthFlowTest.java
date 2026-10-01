package com.zerotrust.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AuthFlowTest extends IntegrationTestSupport {

    private static final String PASSWORD = "password123";

    // 테스트마다 다른 이메일을 쓴다. 서로의 데이터에 영향받지 않아 어떤 순서로 돌려도 결과가 같다.
    private String newEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

    private Response signup(String email) throws Exception {
        return post("/auth/signup", credentials(email, PASSWORD));
    }

    private Response login(String email, String password) throws Exception {
        return post("/auth/login", credentials(email, password));
    }

    private Response refresh(String refreshToken) throws Exception {
        return post("/auth/refresh", "{\"refreshToken\":\"" + refreshToken + "\"}");
    }

    private String credentials(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    @Test
    @DisplayName("가입한 뒤 로그인하면 access와 refresh 토큰을 받는다")
    void loginReturnsTokenPair() throws Exception {
        String email = newEmail();

        assertThat(signup(email).status()).isEqualTo(201);
        Response login = login(email, PASSWORD);

        assertThat(login.status()).isEqualTo(200);
        assertThat(login.get("accessToken")).contains(".");   // JWT는 점으로 나뉜 세 덩어리
        assertThat(login.get("refreshToken")).hasSize(43);
        assertThat(login.get("tokenType")).isEqualTo("Bearer");
    }

    @Test
    @DisplayName("대소문자만 다른 이메일로 다시 가입하면 409")
    void duplicateEmailIsRejected() throws Exception {
        String email = newEmail();
        signup(email);

        Response second = signup(email.toUpperCase());

        assertThat(second.status()).isEqualTo(409);
        assertThat(second.get("code")).isEqualTo("DUPLICATE_EMAIL");
    }

    @Test
    @DisplayName("없는 이메일과 틀린 비밀번호는 구분할 수 없는 같은 응답을 준다")
    void loginFailureDoesNotRevealWhichPartWasWrong() throws Exception {
        String email = newEmail();
        signup(email);

        Response wrongPassword = login(email, "wrong-password");
        Response unknownEmail = login(newEmail(), PASSWORD);

        assertThat(wrongPassword.status()).isEqualTo(401);
        assertThat(unknownEmail.status()).isEqualTo(401);
        assertThat(unknownEmail.body()).isEqualTo(wrongPassword.body());
    }

    @Test
    @DisplayName("쓴 refresh 토큰을 유예 시간이 지난 뒤 다시 쓰면 탈취로 보고 새 토큰까지 전부 무효가 된다")
    void reusingRefreshTokenAfterGraceRevokesEverything() throws Exception {
        String email = newEmail();
        signup(email);
        String first = login(email, PASSWORD).get("refreshToken");

        // 회전: 새 토큰이 나온다
        Response rotated = refresh(first);
        String second = rotated.get("refreshToken");
        assertThat(rotated.status()).isEqualTo(200);
        assertThat(second).isNotEqualTo(first);

        // 유예 시간(테스트 설정 1초)이 지난 뒤 옛 토큰이 다시 온다 = 탈취 신호
        Thread.sleep(1500);
        assertThat(refresh(first).status()).isEqualTo(401);

        // 401만 확인하면 부족하다. "전부 폐기"가 롤백되지 않고 실제로 남았는지 본다.
        assertThat(refresh(second).status()).isEqualTo(401);
    }

    @Test
    @DisplayName("쓴 refresh 토큰이 유예 시간 안에 다시 오면 거절만 하고, 새 토큰은 살려 둔다")
    void reusingRefreshTokenWithinGraceKeepsNewToken() throws Exception {
        String email = newEmail();
        signup(email);
        String first = login(email, PASSWORD).get("refreshToken");
        String second = refresh(first).get("refreshToken");

        // 탭 두 개가 겹쳤거나 응답을 못 받아 재시도한 상황. 옛 토큰으로는 새 토큰을 주지 않는다.
        assertThat(refresh(first).status()).isEqualTo(401);

        // 하지만 정상적으로 받은 새 토큰은 계속 쓸 수 있다.
        assertThat(refresh(second).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("로그아웃한 refresh 토큰으로는 갱신할 수 없다")
    void logoutRevokesRefreshToken() throws Exception {
        String email = newEmail();
        signup(email);
        String refreshToken = login(email, PASSWORD).get("refreshToken");

        Response logout = post("/auth/logout", "{\"refreshToken\":\"" + refreshToken + "\"}");

        assertThat(logout.status()).isEqualTo(204);
        assertThat(refresh(refreshToken).status()).isEqualTo(401);
    }
}
