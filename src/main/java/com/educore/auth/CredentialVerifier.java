package com.educore.auth;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Password verification whose cost does not depend on whether the account exists or how old its hash is.
 * <p>
 * bcrypt work doubles with each cost step. Every verification is padded to the work of one check at the
 * current encoder cost ({@code 2^targetCost} units): a hash of lower cost {@code c} (legacy cost-10 hashes)
 * is followed by {@code 2^(targetCost - c) - 1} checks against a dummy hash of that same cost; an unknown
 * user, or a stored value that is not bcrypt, is checked against a dummy of the target cost. The failure
 * path for an unknown username therefore performs the same bcrypt work as the path for any real account.
 * Hashes of a higher cost than the encoder's are not padded (the encoder never produces them).
 */
@Component
public class CredentialVerifier {

    private static final Pattern BCRYPT = Pattern.compile("^(?:\\{bcrypt})?\\$2[abxy]?\\$(\\d{2})\\$.{53}$");
    private static final int MIN_COST = 4;

    private final PasswordEncoder passwordEncoder;
    private final int targetCost;
    /** Dummy hash per bcrypt cost from {@link #MIN_COST} to {@link #targetCost}; never matches a password. */
    private final Map<Integer, String> dummies = new HashMap<>();

    public CredentialVerifier(PasswordEncoder passwordEncoder) {
        this.passwordEncoder = passwordEncoder;
        SecureRandom random = new SecureRandom();
        String target = passwordEncoder.encode(randomValue(random));
        this.targetCost = cost(target);
        if (targetCost < MIN_COST) {
            throw new IllegalStateException("The password encoder must produce bcrypt hashes");
        }
        dummies.put(targetCost, target);
        for (int cost = MIN_COST; cost < targetCost; cost++) {
            dummies.put(cost, new BCryptPasswordEncoder(cost).encode(randomValue(random)));
        }
    }

    /** Result of {@link #verify}: whether the password matched and the bcrypt work spent, in 2^cost units. */
    public record Verification(boolean matched, long work) {
    }

    /** Verifies {@code rawPassword} against {@code storedHash}; {@code null} means the account does not exist. */
    public Verification verify(String rawPassword, String storedHash) {
        int cost = storedHash == null ? -1 : cost(storedHash);
        if (cost > targetCost) {
            return new Verification(passwordEncoder.matches(rawPassword, storedHash), 1L << cost);
        }
        if (cost < MIN_COST) {
            // Unknown user or a stored value that is not bcrypt (it never matches): one target-cost check.
            boolean matched = storedHash != null && passwordEncoder.matches(rawPassword, storedHash);
            passwordEncoder.matches(rawPassword, dummies.get(targetCost));
            return new Verification(matched, 1L << targetCost);
        }
        boolean matched = passwordEncoder.matches(rawPassword, storedHash);
        long work = 1L << cost;
        String padding = dummies.get(cost);
        long checks = 1L << (targetCost - cost);
        for (long i = 1; i < checks; i++) {
            passwordEncoder.matches(rawPassword, padding);
            work += 1L << cost;
        }
        return new Verification(matched, work);
    }

    /** The bcrypt cost of new hashes; every {@link #verify} call performs {@code 2^targetCost} units of work. */
    public int targetCost() {
        return targetCost;
    }

    /** bcrypt cost of the dummy used for unknown users. */
    int unknownUserDummyCost() {
        return cost(dummies.get(targetCost));
    }

    /** The bcrypt cost encoded in {@code hash} (with or without the {@code {bcrypt}} prefix), or -1. */
    static int cost(String hash) {
        Matcher matcher = BCRYPT.matcher(hash);
        return matcher.matches() ? Integer.parseInt(matcher.group(1)) : -1;
    }

    private static String randomValue(SecureRandom random) {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
