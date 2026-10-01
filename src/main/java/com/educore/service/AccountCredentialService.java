package com.educore.service;

import com.educore.auth.PasswordPolicy;
import com.educore.entity.Account;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.List;

/**
 * Single source of initial credentials for accounts created by the application (API-created and
 * CSV-imported students).
 * <p>
 * Every such account gets a random temporary password of {@value #TEMPORARY_PASSWORD_LENGTH} characters
 * drawn from {@link SecureRandom}; only its hash (through the application {@link PasswordEncoder}) is stored
 * and the account is flagged {@code mustChangePassword}. The plaintext is returned to the caller exactly once
 * and is never logged by this class.
 */
@Service
public class AccountCredentialService {

    public static final int TEMPORARY_PASSWORD_LENGTH = 24;

    // Visually ambiguous characters (0/O, 1/l/I) are left out so an admin can pass the value on reliably.
    static final String UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    static final String LOWER = "abcdefghijkmnopqrstuvwxyz";
    static final String DIGITS = "23456789";
    static final String SYMBOLS = "!#$%&*+-=?@^_";
    static final String ALPHABET = UPPER + LOWER + DIGITS + SYMBOLS;

    private static final List<String> REQUIRED_CLASSES = List.of(UPPER, LOWER, DIGITS, SYMBOLS);

    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final SecureRandom random;

    @Autowired
    public AccountCredentialService(PasswordEncoder passwordEncoder, PasswordPolicy passwordPolicy) {
        this(passwordEncoder, passwordPolicy, new SecureRandom());
    }

    AccountCredentialService(PasswordEncoder passwordEncoder, PasswordPolicy passwordPolicy, SecureRandom random) {
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.random = random;
    }

    /**
     * Generates a temporary password, stores its hash on {@code account} and sets
     * {@code mustChangePassword=true}. The account is not saved.
     *
     * @return the plaintext temporary password; callers that do not hand it to an ADMIN must discard it
     */
    public String assignTemporaryPassword(Account account) {
        String temporaryPassword = generateTemporaryPassword();
        account.setPassword(passwordEncoder.encode(temporaryPassword));
        account.setMustChangePassword(true);
        return temporaryPassword;
    }

    /** A random password that contains every character class and satisfies {@link PasswordPolicy}. */
    String generateTemporaryPassword() {
        while (true) {
            char[] chars = new char[TEMPORARY_PASSWORD_LENGTH];
            // One character from each class first, the rest from the full alphabet, then shuffle.
            for (int i = 0; i < REQUIRED_CLASSES.size(); i++) {
                chars[i] = pick(REQUIRED_CLASSES.get(i));
            }
            for (int i = REQUIRED_CLASSES.size(); i < chars.length; i++) {
                chars[i] = pick(ALPHABET);
            }
            for (int i = chars.length - 1; i > 0; i--) {
                int j = random.nextInt(i + 1);
                char tmp = chars[i];
                chars[i] = chars[j];
                chars[j] = tmp;
            }
            String candidate = new String(chars);
            if (passwordPolicy.violations(candidate).isEmpty()) {
                return candidate;
            }
        }
    }

    private char pick(String alphabet) {
        return alphabet.charAt(random.nextInt(alphabet.length()));
    }
}
