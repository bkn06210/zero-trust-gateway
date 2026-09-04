package com.zerotrust.auth.token;

import com.zerotrust.auth.user.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    // 한 사용자의 살아있는 토큰을 한 방의 UPDATE로 전부 폐기 (재사용 감지 시, 로그아웃 시).
    // 하나씩 불러와 revoke() 하는 것보다 쿼리 한 번이라 빠르다.
    @Modifying
    @Query("UPDATE RefreshToken r SET r.revoked = true WHERE r.user = :user AND r.revoked = false")
    int revokeAllByUser(@Param("user") User user);
}
