package com.zerotrust.gateway.ratelimit;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

// 요청 본문은 소켓에서 한 번만 읽을 수 있다. 처음 읽을 때 바이트를 받아 두고,
// 이후 누가 본문을 달라고 하면 받아 둔 바이트로 새 스트림을 만들어 준다 (양동이).
public class RepeatableBodyRequest extends HttpServletRequestWrapper {

    private final byte[] body;

    private RepeatableBodyRequest(HttpServletRequest request, byte[] body) {
        super(request);
        this.body = body;
    }

    // 본문을 전부 읽어 메모리에 둔다. 상한을 넘으면 읽지 않고 거절한다 (큰 본문으로 메모리를 채우는 공격 방지).
    public static RepeatableBodyRequest wrap(HttpServletRequest request, int maxBytes) throws IOException {
        byte[] body = request.getInputStream().readNBytes(maxBytes + 1);
        if (body.length > maxBytes) {
            throw new BodyTooLargeException(maxBytes);
        }
        return new RepeatableBodyRequest(request, body);
    }

    public String bodyAsString() {
        return new String(body, StandardCharsets.UTF_8);
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream source = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override
            public int read() {
                return source.read();
            }

            @Override
            public boolean isFinished() {
                return source.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener listener) {
                throw new UnsupportedOperationException("비동기 읽기는 지원하지 않는다.");
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }

    public static class BodyTooLargeException extends IOException {
        public BodyTooLargeException(int maxBytes) {
            super("요청 본문이 " + maxBytes + "바이트를 넘습니다.");
        }
    }
}
