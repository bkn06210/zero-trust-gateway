package com.zerotrust.auth.auth;

import com.zerotrust.auth.auth.dto.LoginRequest;
import com.zerotrust.auth.auth.dto.LoginResponse;
import com.zerotrust.auth.auth.dto.SignupRequest;
import com.zerotrust.auth.jwt.JwtProvider;
import com.zerotrust.auth.user.Role;
import com.zerotrust.auth.user.User;
import com.zerotrust.auth.user.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;

    // 존재하지 않는 이메일로 로그인 시도할 때도 BCrypt를 한 번 돌리기 위한 가짜 해시.
    // 실제 해시와 같은 비용(cost)으로 만들어야 응답 시간이 같아진다.
    private final String dummyHash;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtProvider jwtProvider) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtProvider = jwtProvider;
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

    public LoginResponse login(LoginRequest request) {
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

        // 4. 검증 통과 → 토큰 발급
        String accessToken = jwtProvider.createToken(user.getId(), user.getRole());
        return LoginResponse.bearer(accessToken);
    }
}
