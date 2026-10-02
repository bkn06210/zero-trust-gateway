package com.zerotrust.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RoleTest extends IntegrationTestSupport {

    // access token의 가운데 덩어리(payload)를 디코딩한다. 암호화가 아니라서 누구나 읽을 수 있다.
    private String payloadOf(String accessToken) {
        return new String(Base64.getUrlDecoder().decode(accessToken.split("\\.")[1]), StandardCharsets.UTF_8);
    }

    private String loginAndGetPayload(String email, String password) throws Exception {
        Response login = post("/auth/login", "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
        assertThat(login.status()).isEqualTo(200);
        return payloadOf(login.get("accessToken"));
    }

    @Test
    @DisplayName("설정으로 만든 초기 관리자로 로그인하면 토큰의 role이 ADMIN이다")
    void bootstrapAdminGetsAdminToken() throws Exception {
        assertThat(loginAndGetPayload(ADMIN_EMAIL, ADMIN_PASSWORD)).contains("\"role\":\"ADMIN\"");
    }

    @Test
    @DisplayName("가입 요청에 role: ADMIN을 끼워 넣어도 USER로 가입된다")
    void signupCannotChooseRole() throws Exception {
        String email = "user-" + UUID.randomUUID() + "@example.com";

        Response signup = post("/auth/signup",
                "{\"email\":\"" + email + "\",\"password\":\"password123\",\"role\":\"ADMIN\"}");

        assertThat(signup.status()).isEqualTo(201);
        assertThat(loginAndGetPayload(email, "password123")).contains("\"role\":\"USER\"");
    }

    @Test
    @DisplayName("관리자 API는 역할 헤더가 ADMIN일 때만 열린다")
    void adminApiChecksRoleHeader() throws Exception {
        assertThat(getStatus("/admin/users")).isEqualTo(401);
        assertThat(getStatus("/admin/users", "X-User-Role", "USER")).isEqualTo(403);
        assertThat(getStatus("/admin/users", "X-User-Role", "ADMIN")).isEqualTo(200);
    }
}
