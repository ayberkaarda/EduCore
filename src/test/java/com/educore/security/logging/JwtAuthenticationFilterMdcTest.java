package com.educore.security.logging;

import com.educore.common.logging.MdcKeys;
import com.educore.entity.Account;
import com.educore.entity.Role;
import com.educore.repository.AccountRepository;
import com.educore.security.JwtAuthenticationFilter;
import com.educore.security.JwtService;
import io.jsonwebtoken.MalformedJwtException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** {@link JwtAuthenticationFilter} puts {@code userId} into the MDC only for an authenticated request and always removes it. */
class JwtAuthenticationFilterMdcTest {

    private static final String TOKEN = "header.payload.signature";

    private final JwtService jwtService = mock(JwtService.class);
    private final AccountRepository accounts = mock(AccountRepository.class);
    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtService, accounts, Clock.systemUTC());

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    @Test
    void authenticatedRequestCarriesTheAccountIdAndTheMdcIsClearedAfterwards() throws Exception {
        when(jwtService.parse(TOKEN)).thenReturn(new JwtService.AccessTokenClaims(42L, "jti", Instant.now()));
        when(accounts.findById(42L)).thenReturn(Optional.of(
                Account.builder().id(42L).username("mdc-user").role(Role.USER).build()));

        assertThat(userIdSeenDownstream("Bearer " + TOKEN)).isEqualTo("42");
        assertThat(MDC.get(MdcKeys.USER_ID)).isNull();
    }

    @Test
    void rejectedTokenLeavesNoUserId() throws Exception {
        when(jwtService.parse(TOKEN)).thenThrow(new MalformedJwtException("bad"));

        assertThat(userIdSeenDownstream("Bearer " + TOKEN)).isNull();
    }

    @Test
    void anonymousRequestLeavesNoUserId() throws Exception {
        assertThat(userIdSeenDownstream(null)).isNull();
    }

    @Test
    void userIdIsRemovedEvenWhenTheChainFails() {
        when(jwtService.parse(TOKEN)).thenReturn(new JwtService.AccessTokenClaims(7L, "jti", Instant.now()));
        when(accounts.findById(7L)).thenReturn(Optional.of(
                Account.builder().id(7L).username("mdc-fail").role(Role.ADMIN).build()));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/auth/me");
        request.addHeader("Authorization", "Bearer " + TOKEN);

        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            throw new IllegalStateException("downstream failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(MDC.get(MdcKeys.USER_ID)).isNull();
    }

    private String userIdSeenDownstream(String authorization) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/auth/me");
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        AtomicReference<String> seen = new AtomicReference<>();
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> seen.set(MDC.get(MdcKeys.USER_ID)));
        assertThat(MDC.get(MdcKeys.USER_ID)).isNull();
        return seen.get();
    }
}
