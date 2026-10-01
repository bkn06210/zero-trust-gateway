package com.zerotrust.auth.token;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

@Component
public class RefreshTokenCodec {

    // 반드시 SecureRandom. 일반 Random은 다음 값을 예측할 수 있다.
    private final SecureRandom secureRandom = new SecureRandom();

    // 32바이트(256비트) 난수 → URL에 안전한 문자열 43자. 클라이언트에게 주는 원문.
    public String generate() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // DB에 저장하는 값. 원문이 추측 불가능한 난수라 BCrypt 대신 빠른 SHA-256으로 충분하다.
    public String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
        }
    }
}
