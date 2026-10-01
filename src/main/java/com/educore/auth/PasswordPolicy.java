package com.educore.auth;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Rules for new passwords:
 * <ul>
 *   <li>12 to 128 characters;</li>
 *   <li>at most 72 bytes in UTF-8, the input limit of bcrypt (longer values cannot be hashed);</li>
 *   <li>not on the deny list {@code security/common-passwords.txt} (SecLists "10k-most-common"), compared
 *       case-insensitively.</li>
 * </ul>
 * Existing passwords are not re-checked at login; the policy applies when a password is set.
 */
@Component
public class PasswordPolicy {

    public static final int MIN_LENGTH = 12;
    public static final int MAX_LENGTH = 128;
    public static final int MAX_BCRYPT_BYTES = 72;
    static final String DENY_LIST = "security/common-passwords.txt";

    private final Set<String> denyList;

    public PasswordPolicy() {
        this(new ClassPathResource(DENY_LIST));
    }

    PasswordPolicy(Resource denyListResource) {
        this.denyList = load(denyListResource);
    }

    /** Violated rules for {@code candidate}, as stable codes; empty when the password is acceptable. */
    public List<String> violations(String candidate) {
        List<String> violations = new ArrayList<>();
        if (candidate == null || candidate.isEmpty()) {
            violations.add("too_short");
            return violations;
        }
        int length = candidate.codePointCount(0, candidate.length());
        if (length < MIN_LENGTH) {
            violations.add("too_short");
        }
        if (length > MAX_LENGTH) {
            violations.add("too_long");
        } else if (candidate.getBytes(StandardCharsets.UTF_8).length > MAX_BCRYPT_BYTES) {
            violations.add("too_many_bytes");
        }
        if (candidate.isBlank()) {
            violations.add("blank");
        }
        if (denyList.contains(candidate.toLowerCase(Locale.ROOT))) {
            violations.add("common_password");
        }
        return violations;
    }

    int denyListSize() {
        return denyList.size();
    }

    private static Set<String> load(Resource resource) {
        Set<String> entries = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String entry = line.strip();
                if (!entry.isEmpty()) {
                    entries.add(entry.toLowerCase(Locale.ROOT));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read password deny list " + DENY_LIST, e);
        }
        if (entries.isEmpty()) {
            throw new IllegalStateException("Password deny list " + DENY_LIST + " is empty");
        }
        return Set.copyOf(entries);
    }
}
