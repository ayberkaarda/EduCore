package com.educore.account;

import com.educore.common.web.ApiProblemException;
import com.educore.entity.Account;
import com.educore.repository.AccountRepository;
import com.educore.security.ActiveAccount;
import com.educore.security.AuthenticatedUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The caller's own profile. The account is always the authenticated caller; no id is ever taken from input. */
@Service
public class ProfileService {

    private final AccountRepository accountRepository;

    public ProfileService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @Transactional(readOnly = true)
    public ProfileResponse get(AuthenticatedUser user) {
        return ProfileResponse.of(load(user));
    }

    /**
     * Writes only {@code firstName}/{@code lastName} through a field-specific UPDATE, never the whole entity: a
     * concurrent demotion or soft delete committed by an ADMIN is never overwritten. A soft-deleted account
     * updates nothing and answers 404.
     */
    @Transactional
    public ProfileResponse update(AuthenticatedUser user, UpdateProfileRequest request) {
        if (accountRepository.updateOwnName(user.id(), request.firstName().trim(), request.lastName().trim()) == 0) {
            throw ApiProblemException.notFound(AccountAdminService.NOT_FOUND, "Account not found.");
        }
        return ProfileResponse.of(load(user));
    }

    private Account load(AuthenticatedUser user) {
        Account account = accountRepository.findById(user.id()).orElse(null);
        if (!ActiveAccount.isActive(account)) {
            throw ApiProblemException.notFound(AccountAdminService.NOT_FOUND, "Account not found.");
        }
        return account;
    }
}
