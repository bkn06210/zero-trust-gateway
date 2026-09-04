package com.zerotrust.auth.auth;

// 이메일이 없든 비밀번호가 틀리든 이 하나의 예외만 던진다 (계정 열거 방지).
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("이메일 또는 비밀번호가 올바르지 않습니다.");
    }
}
