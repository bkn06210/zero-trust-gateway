package com.zerotrust.auth.common;

// 서버가 지금 처리할 여력이 없어 요청을 받지 않는다. 요청 내용이 틀린 게 아니므로 잠시 후 같은 요청을 다시 보내면 된다.
public class ServerBusyException extends RuntimeException {

    public ServerBusyException() {
        super("요청이 많아 지금은 처리할 수 없습니다. 잠시 후 다시 시도해주세요.");
    }
}
