package com.zerotrust.auth.token;

import com.zerotrust.auth.user.User;
import jakarta.persistence.*;

import java.time.Duration;
import java.time.LocalDateTime;

@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 여러 refresh token(기기마다 하나)이 한 사용자를 가리킨다 → 다대일.
    // LAZY: 토큰을 조회할 때 사용자까지 자동으로 같이 읽지 않는다. 필요할 때만.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // 원문은 저장하지 않는다. SHA-256 16진수 = 64자. 조회 키이므로 unique 인덱스.
    @Column(nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    // 회전 시 true. true인 토큰이 다시 오면 "복사본이 있다" = 탈취 신호.
    @Column(nullable = false)
    private boolean revoked;

    // 언제 폐기됐는지. 폐기 직후에 다시 온 것(탭 겹침, 재시도)과 한참 뒤에 온 것(탈취 의심)을 구분하는 단서.
    private LocalDateTime revokedAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected RefreshToken() {
    }

    public RefreshToken(User user, String tokenHash, LocalDateTime expiresAt) {
        this.user = user;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.revoked = false;
        this.createdAt = LocalDateTime.now();
    }

    // 폐기된 지 주어진 시간 안쪽인가. 폐기 시각 기록이 없으면 오래된 것으로 본다(닫히는 쪽).
    public boolean wasRevokedWithin(Duration window) {
        return revokedAt != null && LocalDateTime.now().isBefore(revokedAt.plus(window));
    }

    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expiresAt);
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public boolean isRevoked() {
        return revoked;
    }

    public LocalDateTime getRevokedAt() {
        return revokedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
