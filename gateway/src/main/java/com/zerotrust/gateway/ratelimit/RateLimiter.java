package com.zerotrust.gateway.ratelimit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

// Redis에 "이 키가 이 시간 창 안에서 몇 번째인가"를 묻는 부품. 고정 창(fixed window) 방식.
@Component
public class RateLimiter {

    // "횟수를 1 올리고, 이번이 처음이면 만료를 건다"를 Redis 안에서 한 덩어리로 실행한다.
    // INCR과 EXPIRE를 따로 보내면 그 사이에 연결이 끊길 때 EXPIRE가 빠져 키가 영원히 남고,
    // 그 사용자는 다시는 로그인할 수 없게 된다. 스크립트는 중간에 끊기지 않는다.
    private static final DefaultRedisScript<List> HIT_SCRIPT = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
                redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return { count, redis.call('TTL', KEYS[1]) }
            """, List.class);

    private final StringRedisTemplate redis;

    public RateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public record Hit(long count, long secondsUntilReset) {
    }

    // 시도 1회를 기록하고, 이 창에서 몇 번째인지와 창이 몇 초 뒤에 끝나는지를 돌려준다.
    @SuppressWarnings("unchecked")
    public Hit hit(String key, Duration window) {
        List<Long> result = redis.execute(HIT_SCRIPT, List.of(key), String.valueOf(window.toSeconds()));
        return new Hit(result.get(0), result.get(1));
    }

    // 로그인 성공 등으로 더 셀 필요가 없어졌을 때 지운다.
    public void reset(List<String> keys) {
        redis.delete(keys);
    }
}
