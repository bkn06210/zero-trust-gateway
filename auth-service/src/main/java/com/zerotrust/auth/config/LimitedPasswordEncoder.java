package com.zerotrust.auth.config;

import com.zerotrust.auth.common.ServerBusyException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

// 진짜 인코더를 감싸서, 해시 계산이 동시에 정해진 개수까지만 돌게 한다.
// 사용처(로그인, 회원가입)는 이 클래스를 모른다. 평소처럼 encode와 matches를 부르면 제한이 알아서 걸린다.
public class LimitedPasswordEncoder implements PasswordEncoder {

    private final PasswordEncoder delegate;
    private final Semaphore permits;
    private final Duration maxWait;

    public LimitedPasswordEncoder(PasswordEncoder delegate, int maxConcurrent, Duration maxWait) {
        this.delegate = delegate;
        // true = 먼저 기다린 요청이 먼저 들어간다. 기본값(false)이면 방금 온 요청이 새치기할 수 있어,
        // 오래 기다린 요청만 시간 초과로 떨어지는 일이 생긴다.
        this.permits = new Semaphore(maxConcurrent, true);
        this.maxWait = maxWait;
    }

    @Override
    public String encode(CharSequence rawPassword) {
        return withPermit(() -> delegate.encode(rawPassword));
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        return withPermit(() -> delegate.matches(rawPassword, encodedPassword));
    }

    // 해시 문자열의 앞머리만 읽는 가벼운 작업이라 자리를 받지 않고 그대로 넘긴다.
    @Override
    public boolean upgradeEncoding(String encodedPassword) {
        return delegate.upgradeEncoding(encodedPassword);
    }

    private <T> T withPermit(Supplier<T> work) {
        // 1. 자리를 받는다. 없으면 maxWait까지만 기다린다. 기다리는 동안 이 스레드는 잠들어 있어 CPU를 쓰지 않는다.
        boolean acquired;
        try {
            acquired = permits.tryAcquire(maxWait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            // 기다리던 중에 서버가 종료되는 경우. "중단 요청이 있었다"는 표시를 되살려 두고 거절한다.
            Thread.currentThread().interrupt();
            throw new ServerBusyException();
        }

        // 2. 끝내 못 받았다. 계산을 시작하지 않고 바로 거절한다. 여기서 CPU를 쓰지 않는 것이 이 클래스의 목적이다.
        if (!acquired) {
            throw new ServerBusyException();
        }

        // 3. 계산. 중간에 예외가 나도 자리는 반드시 돌려준다. 안 돌려주면 자리가 하나씩 영영 사라진다.
        try {
            return work.get();
        } finally {
            permits.release();
        }
    }
}
