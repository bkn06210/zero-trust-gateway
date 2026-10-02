package com.zerotrust.auth.admin;

import com.zerotrust.auth.user.Role;
import com.zerotrust.auth.user.UserRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/admin")
public class AdminController {

    private final UserRepository userRepository;

    public AdminController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    // 전체 회원 목록. Gateway가 이미 ADMIN인지 확인했지만 여기서 한 번 더 본다 (겹겹이 방어).
    // 알려진 한계: 이 헤더도 Gateway를 건너뛰면 위조할 수 있다. 서비스 간 인증(P6)에서 해결한다.
    @GetMapping("/users")
    public List<UserSummary> users(@RequestHeader("X-User-Role") String role) {
        if (!Role.ADMIN.name().equals(role)) {
            throw new ForbiddenException();
        }
        return userRepository.findAll().stream()
                .map(user -> new UserSummary(user.getId(), user.getEmail(), user.getRole(), user.getCreatedAt()))
                .toList();
    }

    public record UserSummary(Long id, String email, Role role, LocalDateTime createdAt) {
    }
}
