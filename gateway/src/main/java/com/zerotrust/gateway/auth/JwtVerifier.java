package com.zerotrust.gateway.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

@Component
public class JwtVerifier {

    private final SecretKey key;

    public JwtVerifier(@Value("${jwt.secret}") String secret) {
        // auth-service의 JwtProvider와 똑같은 방식으로 키를 만든다. 다르면 서명이 절대 안 맞는다.
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    // 통과하면 신원을 돌려주고, 하나라도 어긋나면 JwtException을 던진다.
    public AuthenticatedUser verify(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)            // 이 키로 서명을 다시 계산해 비교한다. 대칭키라 HMAC 계열 알고리즘만 허용된다.
                .build()
                .parseSignedClaims(token)   // 서명 없는 토큰(alg=none)은 여기서 거부. 서명 불일치, 만료(exp)도 여기서 예외.
                .getPayload();

        // 서명이 맞아도 내용이 비어 있으면 통과시키지 않는다.
        String userId = claims.getSubject();
        String role = claims.get("role", String.class);
        if (userId == null || userId.isBlank() || role == null || role.isBlank()) {
            throw new JwtException("필수 클레임(sub, role)이 없습니다.");
        }
        return new AuthenticatedUser(userId, role);
    }
}
