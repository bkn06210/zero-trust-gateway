package com.zerotrust.auth.auth;

import com.zerotrust.auth.auth.dto.LoginRequest;
import com.zerotrust.auth.auth.dto.SignupRequest;
import com.zerotrust.auth.auth.dto.TokenResponse;
import com.zerotrust.auth.jwt.JwtProvider;
import com.zerotrust.auth.token.RefreshToken;
import com.zerotrust.auth.token.RefreshTokenCodec;
import com.zerotrust.auth.token.RefreshTokenRepository;
import com.zerotrust.auth.user.Role;
import com.zerotrust.auth.user.User;
import com.zerotrust.auth.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;
    private final RefreshTokenCodec refreshTokenCodec;
    private final long refreshExpirationDays;

    // 존재하지 않는 이메일로 로그인 시도할 때도 BCrypt를 한 번 돌리기 위한 가짜 해시.
    // 실제 해시와 같은 비용(cost)으로 만들어야 응답 시간이 같아진다.
    private final String dummyHash;

    public AuthService(UserRepository userRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       PasswordEncoder passwordEncoder,
                       JwtProvider jwtProvider,
                       RefreshTokenCodec refreshTokenCodec,
                       @Value("${refresh.expiration-days}") long refreshExpirationDays) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtProvider = jwtProvider;
        this.refreshTokenCodec = refreshTokenCodec;
        this.refreshExpirationDays = refreshExpirationDays;
        this.dummyHash = passwordEncoder.encode("dummy-password-for-timing");
    }

    public Long signup(SignupRequest request) {
        // 1. 정규화: "A@X.com"과 "a@x.com"이 서로 다른 계정이 되는 걸 막는다.
        String email = request.email().trim().toLowerCase();

        // 2. 중복 확인 (친절한 길): 대부분의 중복은 여기서 걸러서 빠르게 응답한다.
        if (userRepository.existsByEmail(email)) {
            throw new DuplicateEmailException();
        }

        // 3. 해시: 원문 비밀번호는 이 줄 이후로 어디에도 남지 않는다.
        String hashed = passwordEncoder.encode(request.password());

        // 4. 저장. DB의 unique 제약이 최후의 방어선 — 2번과 4번 사이에 같은 이메일이 먼저 들어오면 여기서 터진다.
        try {
            User saved = userRepository.save(new User(email, hashed, Role.USER));
            return saved.getId();
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateEmailException();
        }
    }

    public TokenResponse login(LoginRequest request) {
        String email = request.email().trim().toLowerCase();

        // 1. 사용자 조회. 없어도 여기서 바로 실패시키지 않는다 — 아래 해시 비교를 항상 실행하기 위해.
        User user = userRepository.findByEmail(email).orElse(null);

        // 2. 비밀번호 비교. 사용자가 없으면 가짜 해시와 비교해서 "이메일 없음"과 "비번 틀림"의 응답 시간을 같게 만든다.
        String storedHash = (user != null) ? user.getPassword() : dummyHash;
        boolean passwordMatches = passwordEncoder.matches(request.password(), storedHash);

        // 3. 둘 중 하나라도 실패면 같은 예외. 어느 쪽이 틀렸는지 절대 구분해서 알려주지 않는다.
        if (user == null || !passwordMatches) {
            throw new InvalidCredentialsException();
        }

        // 4. 검증 통과 → access + refresh 발급
        return issueTokens(user);
    }

    // 회전: 옛 refresh를 폐기하고 새 access + 새 refresh를 준다.
    // noRollbackFor: 재사용을 감지해 "전부 폐기"한 뒤 예외를 던지는데,
    //   기본 동작대로면 예외 때문에 트랜잭션이 롤백되어 그 폐기까지 취소된다. 이 예외만은 롤백하지 않게 한다.
    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
    public TokenResponse refresh(String rawRefreshToken) {
        // 1. 받은 원문을 해시해서 DB에서 찾는다. 원문은 DB에 없다.
        RefreshToken stored = refreshTokenRepository.findByTokenHash(refreshTokenCodec.hash(rawRefreshToken))
                .orElseThrow(InvalidRefreshTokenException::new);

        // 2. 이미 폐기된 토큰이 다시 왔다 = 누군가 복사본을 갖고 있다. 이 사용자의 토큰을 전부 끊는다.
        if (stored.isRevoked()) {
            revokeAllOnReuse(stored);
            throw new InvalidRefreshTokenException();
        }

        // 3. 만료.
        if (stored.isExpired()) {
            throw new InvalidRefreshTokenException();
        }

        // 4. 폐기. 2번 확인과 이 줄 사이에 같은 토큰의 다른 요청이 먼저 폐기했다면 0이 나온다 → 역시 재사용.
        if (refreshTokenRepository.revokeIfActive(stored.getId()) == 0) {
            revokeAllOnReuse(stored);
            throw new InvalidRefreshTokenException();
        }

        // 5. 새 토큰 한 쌍 발급. 폐기(4)와 발급(5)은 같은 트랜잭션이라 둘 다 되거나 둘 다 안 된다.
        return issueTokens(stored.getUser());
    }

    // 로그아웃: 그 기기의 refresh만 폐기. 없는 토큰이어도 조용히 성공시킨다(여러 번 불러도 결과가 같게).
    @Transactional
    public void logout(String rawRefreshToken) {
        refreshTokenRepository.findByTokenHash(refreshTokenCodec.hash(rawRefreshToken))
                .ifPresent(token -> refreshTokenRepository.revokeIfActive(token.getId()));
    }

    private void revokeAllOnReuse(RefreshToken reused) {
        int revoked = refreshTokenRepository.revokeAllByUser(reused.getUser());
        // 보안 이벤트는 반드시 로그로 남긴다. 토큰 값은 찍지 않고 식별자만.
        log.warn("refresh token 재사용 감지: userId={}, tokenId={}, 폐기된 토큰 수={}",
                reused.getUser().getId(), reused.getId(), revoked);
    }

    private TokenResponse issueTokens(User user) {
        String rawRefreshToken = refreshTokenCodec.generate();
        refreshTokenRepository.save(new RefreshToken(
                user,
                refreshTokenCodec.hash(rawRefreshToken),
                LocalDateTime.now().plusDays(refreshExpirationDays)));

        String accessToken = jwtProvider.createToken(user.getId(), user.getRole());
        return TokenResponse.bearer(accessToken, rawRefreshToken);
    }
}
