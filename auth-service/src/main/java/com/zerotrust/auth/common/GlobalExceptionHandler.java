package com.zerotrust.auth.common;

import com.zerotrust.auth.admin.ForbiddenException;
import com.zerotrust.auth.auth.DuplicateEmailException;
import com.zerotrust.auth.auth.InvalidCredentialsException;
import com.zerotrust.auth.auth.InvalidRefreshTokenException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

// 모든 Controller에서 올라온 예외를 한 곳에서 HTTP 응답으로 바꾼다.
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // 바빠서 거절할 때 "몇 초 뒤 다시 시도하라"고 알려 주는 값.
    private static final String RETRY_AFTER_SECONDS = "1";

    @ExceptionHandler(DuplicateEmailException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse handleDuplicateEmail(DuplicateEmailException e) {
        return new ErrorResponse("DUPLICATE_EMAIL", e.getMessage());
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ErrorResponse handleInvalidCredentials(InvalidCredentialsException e) {
        return new ErrorResponse("INVALID_CREDENTIALS", e.getMessage());
    }

    @ExceptionHandler(InvalidRefreshTokenException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ErrorResponse handleInvalidRefreshToken(InvalidRefreshTokenException e) {
        return new ErrorResponse("INVALID_REFRESH_TOKEN", e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .findFirst()
                .orElse("잘못된 요청입니다.");
        return new ErrorResponse("INVALID_REQUEST", message);
    }

    @ExceptionHandler(ForbiddenException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ErrorResponse handleForbidden(ForbiddenException e) {
        return new ErrorResponse("FORBIDDEN", e.getMessage());
    }

    // 신원 헤더(X-User-Id) 없이 보호된 API를 부른 경우.
    @ExceptionHandler(MissingRequestHeaderException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ErrorResponse handleMissingHeader(MissingRequestHeaderException e) {
        return new ErrorResponse("UNAUTHORIZED", "인증이 필요합니다.");
    }

    // 서버가 바빠서 거절. 429는 "네가 너무 많이 보냈다", 503은 "서버가 지금 못 받는다"라서 여기는 503이다.
    // 몰릴 때는 이 응답이 초당 수십 번 나가므로 로그를 남기지 않는다. 횟수는 지표의 503 응답 수로 본다.
    @ExceptionHandler(ServerBusyException.class)
    public ResponseEntity<ErrorResponse> handleServerBusy(ServerBusyException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
                .body(new ErrorResponse("SERVER_BUSY", e.getMessage()));
    }

    // 예상 못한 예외: 내부 정보(스택트레이스, SQL 등)는 절대 응답에 싣지 않고 로그로만 남긴다.
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ErrorResponse handleUnexpected(Exception e) {
        log.error("처리되지 않은 예외", e);
        return new ErrorResponse("INTERNAL_ERROR", "서버 오류가 발생했습니다.");
    }
}
