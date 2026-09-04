package com.zerotrust.auth.auth.dto;

import jakarta.validation.constraints.NotBlank;

// 로그인은 형식 검증을 느슨하게 둔다. "이메일 형식이 아님" 같은 400도 계정 정보 힌트가 될 수 있어서.
public record LoginRequest(
        @NotBlank String email,
        @NotBlank String password
) {
}
