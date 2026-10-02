package com.zerotrust.gateway.auth;

import org.springframework.stereotype.Component;

import java.util.Map;

// "어느 경로에 어떤 역할이 필요한가"를 한 곳에 모아 둔다.
@Component
public class AccessRules {

    // 경로 앞부분 → 필요한 역할.
    // 공개 경로는 정확히 일치할 때만 열었지만, 보호 규칙은 반대로 넓게(앞부분 일치) 건다.
    // /admin/ 아래에 새 API가 생겨도 자동으로 보호된다.
    private static final Map<String, String> REQUIRED_ROLE_BY_PREFIX = Map.of(
            "/admin/", "ADMIN"
    );

    // 이 사용자가 이 경로에 들어가도 되는가.
    public boolean isAllowed(String path, AuthenticatedUser user) {
        // 대소문자를 바꿔 규칙을 피해 가지 못하게 소문자로 맞춰 비교한다.
        String normalized = path.toLowerCase();
        return REQUIRED_ROLE_BY_PREFIX.entrySet().stream()
                .filter(rule -> normalized.startsWith(rule.getKey()))
                .allMatch(rule -> rule.getValue().equals(user.role()));
    }
}
