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

/**
 * One login (or current-password) verification; the username is stored only as a peppered hash. {@code clientKey}
 * is the canonical client key of {@code ip} (IPv4 address or IPv6 /64, V23): the lockout applies per
 * (username hash, client key) pair.
 */
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

    @Column(nullable = false, length = 64)
    private String clientKey;

    @Column(nullable = false)
    private boolean success;

    @Column(nullable = false)
    private Instant at;

    public LoginAttempt(String usernameHash, String ip, String clientKey, boolean success, Instant at) {
        this.usernameHash = usernameHash;
        this.ip = ip;
        this.clientKey = clientKey;
        this.success = success;
        this.at = at;
    }
}
