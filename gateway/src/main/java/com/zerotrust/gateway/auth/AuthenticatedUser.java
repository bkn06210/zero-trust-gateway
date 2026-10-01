package com.zerotrust.gateway.auth;

// 검증을 통과한 토큰에서 꺼낸 신원. Gateway 안에서는 이 객체만 믿는다.
public record AuthenticatedUser(String userId, String role) {
}
