package com.zerotrust.auth.token;

import com.zerotrust.auth.user.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    // "아직 살아있으면 폐기해"를 한 문장으로. 바뀐 줄 수(0 또는 1)를 돌려준다.
    // 같은 토큰으로 동시에 두 요청이 와도 DB가 한 명만 1을 받게 해준다.
    @Modifying
    @Query("UPDATE RefreshToken r SET r.revoked = true, r.revokedAt = :now WHERE r.id = :id AND r.revoked = false")
    int revokeIfActive(@Param("id") Long id, @Param("now") LocalDateTime now);

    // 한 사용자의 살아있는 토큰을 한 방의 UPDATE로 전부 폐기 (재사용 감지 시).
    // 하나씩 불러와 revoke() 하는 것보다 쿼리 한 번이라 빠르다.
    @Modifying
    @Query("UPDATE RefreshToken r SET r.revoked = true, r.revokedAt = :now WHERE r.user = :user AND r.revoked = false")
    int revokeAllByUser(@Param("user") User user, @Param("now") LocalDateTime now);
}
