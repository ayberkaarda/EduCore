package com.educore.account;

import com.educore.common.web.ApiProblemException;
import com.educore.entity.Account;
import com.educore.repository.AccountRepository;
import com.educore.security.ActiveAccount;
import com.educore.security.AuthenticatedUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/** The caller's own profile. The account is always the authenticated caller; no id is ever taken from input. */
@Service
public class ProfileService {

    private final AccountRepository accountRepository;
    private final Clock clock;

    public ProfileService(AccountRepository accountRepository, Clock clock) {
        this.accountRepository = accountRepository;
        this.clock = clock;
    }

    /** The profile of an active account, or of an account in its deletion grace period (with its status). */
    @Transactional(readOnly = true)
    public ProfileResponse get(AuthenticatedUser user) {
        Account account = accountRepository.findById(user.id()).orElse(null);
        if (!ActiveAccount.mayAuthenticate(account, clock.instant())) {
            throw ApiProblemException.notFound(AccountAdminService.NOT_FOUND, "Account not found.");
        }
        return ProfileResponse.of(account);
    }

    /**
     * Writes only {@code firstName}/{@code lastName} through a field-specific UPDATE, never the whole entity: a
     * concurrent demotion, soft delete or deletion request is never overwritten. An account that is no longer
     * active updates nothing and answers 404.
     */
    @Transactional
    public ProfileResponse update(AuthenticatedUser user, UpdateProfileRequest request) {
        if (accountRepository.updateOwnName(user.id(), request.firstName().trim(), request.lastName().trim()) == 0) {
            throw ApiProblemException.notFound(AccountAdminService.NOT_FOUND, "Account not found.");
        }
        Account account = accountRepository.findById(user.id()).filter(ActiveAccount::isActive)
                .orElseThrow(() -> ApiProblemException.notFound(AccountAdminService.NOT_FOUND, "Account not found."));
        return ProfileResponse.of(account);
    }
}
