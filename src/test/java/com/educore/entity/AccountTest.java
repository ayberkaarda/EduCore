package com.educore.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AccountTest {

    @Test
    void toStringNeverContainsThePassword() {
        Account account = Account.builder()
                .username("to-string-user")
                .password("to-string-password-hash-value")
                .role(Role.USER)
                .build();

        assertThat(account.toString()).contains("to-string-user").doesNotContain("to-string-password-hash-value");
    }
}
