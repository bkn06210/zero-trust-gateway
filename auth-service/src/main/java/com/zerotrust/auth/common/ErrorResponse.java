package com.zerotrust.auth.common;

// 모든 에러 응답의 공통 모양. 클라이언트는 code로 분기하고, message는 사람이 읽는 용도.
public record ErrorResponse(String code, String message) {
}
