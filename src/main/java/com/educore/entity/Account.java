package com.educore.entity;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Account implements UserDetails {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String username;

    // Never part of toString(): the hash must not reach logs.
    @ToString.Exclude
    @Column(nullable = false)
    private String password;

    private String firstName;
    private String lastName;

    @Column(unique = true)
    private String studentNumber;

    @Enumerated(EnumType.STRING)
    private Role role;

    @Column(unique = true)
    private String ipAddress;

    // Optimistic lock: every entity update checks and increments it, so a write based on a stale read fails
    // (ObjectOptimisticLockingFailureException) instead of overwriting a newer role or status.
    // Null for an account that has not been persisted yet.
    @JsonIgnore
    @Version
    private Long version;

    /** Lifecycle state (V21); only {@link AccountStatus#ACTIVE} accounts have full access. */
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AccountStatus status = AccountStatus.ACTIVE;

    /** When the account left {@code ACTIVE}; {@code null} while active. */
    private Instant deletedAt;

    /** End of the grace period of a {@code PENDING_DELETION} account; {@code null} otherwise. */
    private Instant deleteAfter;

    /**
     * Session epoch (V22): copied into every access token ({@code sep} claim) and compared on every request, so
     * incrementing it ends every access token issued before (deletion request, soft delete, restore).
     */
    @JsonIgnore
    @Builder.Default
    @Column(nullable = false)
    private int sessionEpoch = 0;

    // Set for accounts whose initial password was supplied by an operator (e.g. the bootstrap ADMIN).
    // Not exposed through the API yet: ignored for JSON input and output.
    @JsonIgnore
    @Builder.Default
    @Column(nullable = false)
    private boolean mustChangePassword = false;

    // Last check before the insert: an account built without a status starts ACTIVE.
    @PrePersist
    protected void onCreate() {
        if (this.status == null) {
            this.status = AccountStatus.ACTIVE;
        }
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override public String getPassword() { return password; }
    @Override public String getUsername() { return username; }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled() { return true; }
}
