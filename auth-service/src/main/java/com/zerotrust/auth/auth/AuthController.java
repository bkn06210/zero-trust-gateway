package com.zerotrust.auth.auth;

import com.zerotrust.auth.auth.dto.LoginRequest;
import com.zerotrust.auth.auth.dto.LoginResponse;
import com.zerotrust.auth.auth.dto.SignupRequest;
import com.zerotrust.auth.auth.dto.SignupResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public SignupResponse signup(@Valid @RequestBody SignupRequest request) {
        Long id = authService.signup(request);
        return new SignupResponse(id);
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }
}
