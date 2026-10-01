package com.educore.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** One login (or current-password) verification; the username is stored only as a peppered hash. */
@Entity
@Table(name = "login_attempt")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LoginAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String usernameHash;

    @Column(nullable = false, length = 45)
    private String ip;

    @Column(nullable = false)
    private boolean success;

    @Column(nullable = false)
    private Instant at;

    public LoginAttempt(String usernameHash, String ip, boolean success, Instant at) {
        this.usernameHash = usernameHash;
        this.ip = ip;
        this.success = success;
        this.at = at;
    }
}
