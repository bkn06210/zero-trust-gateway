package com.zerotrust.auth.admin;

import com.zerotrust.auth.user.Role;
import com.zerotrust.auth.user.User;
import com.zerotrust.auth.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

// 앱이 뜰 때 한 번 실행된다. 설정에 관리자 계정이 적혀 있고 아직 없으면 만든다.
// 회원가입 API로는 ADMIN을 만들 수 없으므로, 첫 관리자는 서버를 운영하는 쪽만 만들 수 있다.
@Component
public class AdminInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminInitializer.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String adminEmail;
    private final String adminPassword;

    public AdminInitializer(UserRepository userRepository,
                            PasswordEncoder passwordEncoder,
                            @Value("${admin.email:}") String adminEmail,
                            @Value("${admin.password:}") String adminPassword) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.adminEmail = adminEmail.trim().toLowerCase();
        this.adminPassword = adminPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        // 설정이 비어 있으면 아무것도 하지 않는다. 기본 비밀번호 같은 것은 두지 않는다.
        if (adminEmail.isBlank() || adminPassword.isBlank()) {
            return;
        }
        if (userRepository.existsByEmail(adminEmail)) {
            return;
        }
        userRepository.save(new User(adminEmail, passwordEncoder.encode(adminPassword), Role.ADMIN));
        log.info("초기 관리자 계정을 만들었습니다: {}", adminEmail);
    }
}
