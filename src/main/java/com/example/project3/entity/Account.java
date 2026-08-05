package com.example.project3.entity;
import jakarta.persistence.Column;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
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

    // ÇÖZÜM BURADA: Builder kullanıldığında 0 değerinin ezilmemesi için eklendi
    @Builder.Default
    @Column(nullable = false, columnDefinition = "int default 0")
    private Integer deleted = 0;

    // Veritabanına kaydetmeden hemen önce son güvenlik kontrolü
    @PrePersist
    protected void onCreate() {
        if (this.deleted == null) {
            this.deleted = 0;
        }
    }

    public Integer getDeleted() {
        return deleted;
    }

    public void setDeleted(Integer deleted) {
        this.deleted = deleted;
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