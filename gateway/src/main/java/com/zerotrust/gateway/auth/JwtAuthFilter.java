package com.zerotrust.gateway.auth;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

// 모든 요청이 Controller에 닿기 전에 반드시 거치는 검문소.
// 인증(누구인가, 실패 시 401)을 먼저 하고, 인가(해도 되는가, 실패 시 403)를 그 다음에 한다.
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)   // 로그인 횟수 제한 필터 다음
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);

    // 검증된 신원을 다음 단계(요청 전달)로 넘길 때 쓰는 이름표.
    public static final String USER_ATTRIBUTE = "authenticatedUser";

    private static final String BEARER_PREFIX = "Bearer ";

    // 기본은 전부 막고, 토큰 없이 열어줄 곳만 적는다 (허용 목록).
    // "메서드 + 경로"가 글자 그대로 일치할 때만 연다. 조금이라도 다르면 토큰을 요구한다.
    private static final Set<String> PUBLIC_ENDPOINTS = Set.of(
            "POST /auth/signup",
            "POST /auth/login",
            "POST /auth/refresh",
            "POST /auth/logout"
    );

    private final JwtVerifier jwtVerifier;
    private final AccessRules accessRules;

    public JwtAuthFilter(JwtVerifier jwtVerifier, AccessRules accessRules) {
        this.jwtVerifier = jwtVerifier;
        this.accessRules = accessRules;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // 1. 공개 경로면 검사 없이 다음으로.
        if (PUBLIC_ENDPOINTS.contains(request.getMethod() + " " + request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }

        // 2. Authorization 헤더에서 토큰을 꺼낸다. 없거나 형식이 다르면 401.
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            log.warn("인증 실패: {} {} 사유=Authorization 헤더 없음 또는 Bearer 형식 아님",
                    request.getMethod(), request.getRequestURI());
            unauthorized(response);
            return;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();

        // 3. 인증: 서명, 만료, 필수 클레임 검증. 실패 사유는 전부 JwtException으로 온다.
        AuthenticatedUser user;
        try {
            user = jwtVerifier.verify(token);
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("인증 실패: {} {} 사유={}", request.getMethod(), request.getRequestURI(), e.getClass().getSimpleName());
            unauthorized(response);
            return;
        }

        // 4. 인가: 누구인지는 확인됐다. 이제 이 역할로 이 경로에 들어가도 되는지 본다.
        //    role은 서명 검증을 통과한 토큰에서 꺼낸 값이라 클라이언트가 바꿀 수 없다.
        if (!accessRules.isAllowed(request.getRequestURI(), user)) {
            log.warn("권한 없음: {} {} userId={} role={}",
                    request.getMethod(), request.getRequestURI(), user.userId(), user.role());
            writeError(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN", "접근 권한이 없습니다.");
            return;
        }

        // 5. 통과. 검증된 신원을 요청에 붙이고 다음 단계로 넘긴다.
        request.setAttribute(USER_ATTRIBUTE, user);
        filterChain.doFilter(request, response);
    }

    // 실패 사유는 서버 로그에만 남기고, 클라이언트에는 구분 없이 같은 401을 준다.
    private void unauthorized(HttpServletResponse response) throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHORIZED", "인증이 필요합니다.");
    }

    // Filter는 Controller보다 앞이라 @RestControllerAdvice가 잡아주지 못한다. 응답을 직접 써야 한다.
    private void writeError(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }
}
