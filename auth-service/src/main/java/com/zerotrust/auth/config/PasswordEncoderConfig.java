package com.zerotrust.auth.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;

@Configuration
public class PasswordEncoderConfig {

    private static final Logger log = LoggerFactory.getLogger(PasswordEncoderConfig.class);

    // 앱 전체에서 단 하나의 인코더를 공유한다. 인터페이스(PasswordEncoder)로 노출해서 나중에 알고리즘을 바꿔도 사용처는 안 건드린다.
    // BCrypt를 그대로 내주지 않고 동시 실행 제한으로 감싸서 내준다. 사용처는 바뀐 줄 모른다.
    @Bean
    public PasswordEncoder passwordEncoder(@Value("${bcrypt.max-concurrent}") int maxConcurrent,
                                           @Value("${bcrypt.max-wait-ms}") long maxWaitMs) {
        // 0이면 서버의 CPU 개수를 쓴다. BCrypt는 CPU만 쓰는 계산이라 CPU 개수보다 많이 동시에 돌려도 처리량은 늘지 않는다.
        int permits = maxConcurrent > 0 ? maxConcurrent : Runtime.getRuntime().availableProcessors();
        log.info("BCrypt 동시 실행 상한: {}개, 최대 대기: {}ms", permits, maxWaitMs);
        return new LimitedPasswordEncoder(new BCryptPasswordEncoder(), permits, Duration.ofMillis(maxWaitMs));
    }
}
