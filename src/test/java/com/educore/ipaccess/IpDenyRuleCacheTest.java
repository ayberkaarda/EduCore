package com.educore.ipaccess;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Snapshot semantics of the deny rule cache: scopes, expiry, TTL, invalidation and load failures. */
class IpDenyRuleCacheTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-02T10:00:00Z"));
    private final Clock clock = new Clock() {
        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };
    private final IpDenyRuleRepository repository = mock(IpDenyRuleRepository.class);
    private final IpDenyRuleCache cache = new IpDenyRuleCache(repository, clock, Duration.ofSeconds(60));

    private static IpDenyRule rule(String value, IpDenyRuleSource source, Instant expiresAt) {
        IpDenyRule rule = new IpDenyRule();
        rule.applyRange(IpRangeKind.STATIC, Ipv4Range.single(Ipv4.parse(value)));
        rule.setSource(source);
        rule.setExpiresAt(expiresAt);
        return rule;
    }

    @Test
    void manualRulesDenyEverythingAutoRulesOnlyTheLogin() {
        when(repository.findActive(any())).thenReturn(List.of(
                rule("198.18.1.1", IpDenyRuleSource.MANUAL, null),
                rule("198.18.1.2", IpDenyRuleSource.AUTO, now.get().plusSeconds(3600)),
                rule("198.18.1.3", IpDenyRuleSource.AUTO, now.get().plusSeconds(3600)),
                rule("198.18.1.3", IpDenyRuleSource.MANUAL, null)));

        assertThat(cache.denial(Ipv4.parse("198.18.1.1"))).isEqualTo(IpDenyRuleCache.Denial.ALL);
        assertThat(cache.denial(Ipv4.parse("198.18.1.2"))).isEqualTo(IpDenyRuleCache.Denial.LOGIN);
        assertThat(cache.denial(Ipv4.parse("198.18.1.3"))).isEqualTo(IpDenyRuleCache.Denial.ALL);
        assertThat(cache.denial(Ipv4.parse("198.18.1.4"))).isEqualTo(IpDenyRuleCache.Denial.NONE);
    }

    @Test
    void reloadsAfterTheTtlOrAnInvalidationAndExpiresRulesOnTime() {
        when(repository.findActive(any())).thenReturn(
                List.of(rule("198.18.2.1", IpDenyRuleSource.MANUAL, now.get().plusSeconds(30))));

        assertThat(cache.isDenied(Ipv4.parse("198.18.2.1"))).isTrue();
        now.set(now.get().plusSeconds(31));
        assertThat(cache.isDenied(Ipv4.parse("198.18.2.1"))).as("expired inside the TTL").isFalse();
        verify(repository, times(1)).findActive(any());

        now.set(now.get().plusSeconds(30));
        cache.isDenied(Ipv4.parse("198.18.2.1"));
        cache.invalidateAfterCommit();
        cache.isDenied(Ipv4.parse("198.18.2.1"));
        verify(repository, times(3)).findActive(any());
    }

    @Test
    void failsClosedWithoutAnyEarlierSnapshot() {
        when(repository.findActive(any())).thenThrow(new DataAccessResourceFailureException("database down"));

        assertThatThrownBy(() -> cache.denial(Ipv4.parse("198.18.3.1")))
                .isInstanceOf(IpDenyRuleCache.UnavailableException.class);
    }

    @Test
    void keepsTheLastSnapshotWhenAReloadFails() {
        when(repository.findActive(any()))
                .thenReturn(List.of(rule("198.18.4.1", IpDenyRuleSource.MANUAL, null)))
                .thenThrow(new DataAccessResourceFailureException("database down"))
                .thenReturn(List.of());

        assertThat(cache.isDenied(Ipv4.parse("198.18.4.1"))).isTrue();
        cache.invalidateAfterCommit();
        assertThat(cache.isDenied(Ipv4.parse("198.18.4.1"))).as("stale rules stay in force").isTrue();
        // No reload is attempted until the retry delay has passed.
        assertThat(cache.isDenied(Ipv4.parse("198.18.4.1"))).isTrue();
        verify(repository, times(2)).findActive(any());

        now.set(now.get().plus(IpDenyRuleCache.RETRY_AFTER_FAILURE).plusSeconds(1));
        assertThat(cache.isDenied(Ipv4.parse("198.18.4.1"))).isFalse();
    }
}
