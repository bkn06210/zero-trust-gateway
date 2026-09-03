package com.zerotrust.auth.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SignupRequest(
        @NotBlank @Email @Size(max = 100)
        String email,

        // BCrypt는 72바이트까지만 보기 때문에 그 이상은 잘라버린다. 상한을 걸어 "긴 비밀번호가 조용히 잘리는" 일을 막는다.
        @NotBlank @Size(min = 8, max = 72)
        String password
) {
}
