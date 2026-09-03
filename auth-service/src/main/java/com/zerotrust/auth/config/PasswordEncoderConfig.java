package com.zerotrust.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class PasswordEncoderConfig {

    // 앱 전체에서 단 하나의 인코더를 공유한다. 인터페이스(PasswordEncoder)로 노출해서 나중에 알고리즘을 바꿔도 사용처는 안 건드린다.
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
