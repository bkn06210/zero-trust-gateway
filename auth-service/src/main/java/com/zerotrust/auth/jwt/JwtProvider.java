package com.zerotrust.auth.jwt;

import com.zerotrust.auth.user.Role;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

@Component
public class JwtProvider {

    private final SecretKey key;
    private final long expirationMinutes;

    public JwtProvider(@Value("${jwt.secret}") String secret,
                       @Value("${jwt.expiration-minutes}") long expirationMinutes) {
        // 문자열 비밀키를 HMAC-SHA 키 객체로 변환. 32바이트 미만이면 여기서 앱이 뜨지 않는다 (약한 키 차단).
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMinutes = expirationMinutes;
    }

    public String createToken(Long userId, Role role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))                                  // sub: 누구인지 (id만, 개인정보 없음)
                .claim("role", role.name())                                        // role: Gateway가 DB 없이 권한 판단
                .id(UUID.randomUUID().toString())                                  // jti: 토큰 고유번호, 블랙리스트용
                .issuedAt(Date.from(now))                                          // iat
                .expiration(Date.from(now.plus(expirationMinutes, ChronoUnit.MINUTES))) // exp
                .signWith(key)                                                     // HS256 서명
                .compact();
    }
}
