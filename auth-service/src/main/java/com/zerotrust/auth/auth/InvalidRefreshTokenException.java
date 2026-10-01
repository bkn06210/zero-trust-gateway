package com.zerotrust.auth.auth;

// 없음 / 만료 / 폐기됨을 구분하지 않고 이 하나만 던진다.
public class InvalidRefreshTokenException extends RuntimeException {

    public InvalidRefreshTokenException() {
        super("유효하지 않은 리프레시 토큰입니다. 다시 로그인해주세요.");
    }
}
