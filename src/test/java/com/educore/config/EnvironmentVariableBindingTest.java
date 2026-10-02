package com.educore.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards against the relaxed-binding trap: every {@code EDUCORE_*} variable documented in {@code .env.example} or
 * set by a root {@code docker-compose*.yml} must reach the application through an explicit {@code ${NAME}} /
 * {@code ${NAME:default}} placeholder in {@code application.yml} (the base file, so it holds in every profile),
 * unless it is listed below as a variable that only Docker Compose, PostgreSQL or the edge containers read.
 * A new variable without a placeholder (or a stale exemption) fails the build.
 */
class EnvironmentVariableBindingTest {

    /** Read by compose or by other containers, never by the Spring application (with the reason). */
    private static final Map<String, String> NOT_READ_BY_THE_APPLICATION = Map.of(
            "EDUCORE_DB_NAME", "compose builds EDUCORE_DB_URL from it and creates the database in postgres-db",
            "EDUCORE_PUBLIC_API_URL", "build argument of the edge image (prerendering), docker-compose.prod.yml",
            "EDUCORE_HTTP_PORT", "host port of the edge nginx (docker-compose.prod.yml)",
            "EDUCORE_HTTPS_PORT", "host port of the edge nginx (docker-compose.prod.yml)",
            "EDUCORE_TLS_SOURCE", "certificate volume of the edge nginx",
            "EDUCORE_TLS_DOMAINS", "certbot container",
            "EDUCORE_ACME_EMAIL", "certbot container",
            "EDUCORE_ACME_STAGING", "certbot container");

    private static final Pattern VARIABLE = Pattern.compile("\\bEDUCORE_[A-Z0-9_]+\\b");
    private static final Path ROOT = Path.of("").toAbsolutePath();

    @Test
    void everyDocumentedVariableHasAnExplicitPlaceholderInApplicationYml() throws IOException {
        Set<String> documented = new TreeSet<>();
        documented.addAll(variablesIn(".env.example"));
        for (String compose : new String[] {"docker-compose.yml", "docker-compose.prod.yml", "docker-compose.dev.yml"}) {
            documented.addAll(variablesIn(compose));
        }
        assertThat(documented).as("variables found").contains("EDUCORE_SEO_BASE_URL", "EDUCORE_JWT_SECRET");

        String applicationYml = read("src/main/resources/application.yml");
        Set<String> unbound = new TreeSet<>();
        for (String variable : documented) {
            if (NOT_READ_BY_THE_APPLICATION.containsKey(variable)) {
                continue;
            }
            if (!Pattern.compile("\\$\\{" + variable + "(:[^}]*)?}").matcher(applicationYml).find()) {
                unbound.add(variable);
            }
        }
        assertThat(unbound).as("EDUCORE_* variables without a ${NAME} placeholder in application.yml").isEmpty();
        assertThat(documented).as("stale exemptions").containsAll(NOT_READ_BY_THE_APPLICATION.keySet());
    }

    private static Set<String> variablesIn(String file) throws IOException {
        Set<String> found = new TreeSet<>();
        Matcher matcher = VARIABLE.matcher(read(file));
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return found;
    }

    private static String read(String file) throws IOException {
        return Files.readString(ROOT.resolve(file), StandardCharsets.UTF_8);
    }
}
