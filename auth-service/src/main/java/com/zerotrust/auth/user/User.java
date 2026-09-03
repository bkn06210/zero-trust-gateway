package com.zerotrust.auth.user;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String email;

    // BCrypt 결과(60자)만 저장한다. 원문 비밀번호는 절대 여기 들어오면 안 된다.
    @Column(nullable = false, length = 60)
    private String password;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // JPA가 DB에서 읽어올 때 내부적으로 쓰는 생성자. 외부에서 쓰지 못하게 protected.
    protected User() {
    }

    public User(String email, String hashedPassword, Role role) {
        this.email = email;
        this.password = hashedPassword;
        this.role = role;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPassword() {
        return password;
    }

    public Role getRole() {
        return role;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
