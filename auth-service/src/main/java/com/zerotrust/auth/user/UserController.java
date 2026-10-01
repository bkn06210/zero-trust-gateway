package com.zerotrust.auth.user;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/users")
public class UserController {

    private final UserRepository userRepository;

    public UserController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    // "내 정보". 누구인지는 Gateway가 붙여준 X-User-Id 헤더로 안다.
    // 알려진 한계: 이 헤더를 그대로 믿는다. Gateway를 거치지 않고 직접 호출하면 아무 id나 사칭할 수 있다.
    @GetMapping("/me")
    public ResponseEntity<MeResponse> me(@RequestHeader("X-User-Id") Long userId) {
        return userRepository.findById(userId)
                .map(user -> ResponseEntity.ok(new MeResponse(user.getId(), user.getEmail(), user.getRole())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // 엔티티를 그대로 돌려주면 비밀번호 해시까지 나간다. 응답용 그릇을 따로 둔다.
    public record MeResponse(Long id, String email, Role role) {
    }
}
