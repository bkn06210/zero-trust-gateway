package com.zerotrust.auth.auth;

import com.zerotrust.auth.auth.dto.SignupRequest;
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

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
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
}
