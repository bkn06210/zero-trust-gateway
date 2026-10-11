package com.zerotrust.auth.config;

import com.zerotrust.auth.common.ServerBusyException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 진짜 BCrypt 대신 "정해진 시간만큼 걸리는 가짜 인코더"를 감싸서, 제한 장치의 동작만 따로 확인한다.
// 스프링도 DB도 띄우지 않으므로 Docker 없이 바로 돈다.
class LimitedPasswordEncoderTest {

    // 계산 한 번에 workMillis가 걸리고, 동시에 몇 개가 계산 중이었는지 최댓값을 기억한다.
    private static class SlowEncoder implements PasswordEncoder {
        private final long workMillis;
        private final AtomicInteger running = new AtomicInteger();
        private final AtomicInteger maxRunning = new AtomicInteger();
        private final AtomicInteger calls = new AtomicInteger();
        private final CountDownLatch entered = new CountDownLatch(1);

        SlowEncoder(long workMillis) {
            this.workMillis = workMillis;
        }

        @Override
        public String encode(CharSequence rawPassword) {
            work();
            return "hash";
        }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            work();
            return true;
        }

        private void work() {
            calls.incrementAndGet();
            maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
            entered.countDown();
            try {
                Thread.sleep(workMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                running.decrementAndGet();
            }
        }
    }

    @Test
    @DisplayName("20개가 동시에 몰려도 계산은 상한인 2개씩만 돈다")
    void neverRunsMoreThanLimit() throws Exception {
        SlowEncoder slow = new SlowEncoder(50);
        PasswordEncoder limited = new LimitedPasswordEncoder(slow, 2, Duration.ofSeconds(10));

        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return limited.matches("password", "hash");
                }));
            }
            start.countDown();
            for (Future<Boolean> future : futures) {
                assertThat(future.get()).isTrue();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(slow.calls.get()).isEqualTo(20);
        assertThat(slow.maxRunning.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("자리가 안 나면 정해진 시간만 기다리다 거절하고, 계산은 시작하지 않는다")
    void rejectsWithoutComputingWhenNoPermit() throws Exception {
        SlowEncoder slow = new SlowEncoder(2000);
        PasswordEncoder limited = new LimitedPasswordEncoder(slow, 1, Duration.ofMillis(100));

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            // 하나뿐인 자리를 2초 동안 차지시킨다.
            pool.submit(() -> limited.encode("password"));
            slow.entered.await();

            long begin = System.nanoTime();
            assertThatThrownBy(() -> limited.matches("password", "hash"))
                    .isInstanceOf(ServerBusyException.class);
            long waitedMillis = Duration.ofNanos(System.nanoTime() - begin).toMillis();

            // 앞 계산이 끝나는 2초까지 붙잡혀 있지 않고 그 전에 포기했다.
            assertThat(waitedMillis).isLessThan(1000);
            // 거절된 요청은 가짜 인코더까지 가지 않았다. 호출은 자리를 차지한 한 번뿐이다.
            assertThat(slow.calls.get()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("계산 중에 예외가 나도 자리를 돌려준다")
    void releasesPermitWhenWorkFails() {
        PasswordEncoder failing = new PasswordEncoder() {
            @Override
            public String encode(CharSequence rawPassword) {
                throw new IllegalStateException("계산 실패");
            }

            @Override
            public boolean matches(CharSequence rawPassword, String encodedPassword) {
                return true;
            }
        };
        PasswordEncoder limited = new LimitedPasswordEncoder(failing, 1, Duration.ofMillis(100));

        assertThatThrownBy(() -> limited.encode("password")).isInstanceOf(IllegalStateException.class);

        // 자리를 돌려주지 않았다면 하나뿐인 자리가 사라져 여기서 ServerBusyException이 난다.
        assertThat(limited.matches("password", "hash")).isTrue();
    }
}
