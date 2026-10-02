package com.educore.web;

import com.educore.authz.AuthzIntegrationSupport;
import com.educore.entity.Account;
import com.educore.entity.Role;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Account searches against PostgreSQL: {@code %} and {@code _} in the search term are literal characters
 * (escaped LIKE pattern), and the whitelisted sort keys order the page.
 */
class AccountSearchIT extends AuthzIntegrationSupport {

    @Test
    void percentAndUnderscoreInTheSearchTermMatchLiterally() throws Exception {
        Account admin = account(Role.ADMIN);
        String tag = "Lk" + UUID.randomUUID().toString().substring(0, 6);
        Account percent = student(tag + "50%off", "Probe");
        Account plain = student(tag + "50xoff", "Probe");
        Account underscore = student(tag + "a_b", "Probe");
        Account noUnderscore = student(tag + "axb", "Probe");

        assertThat(firstNames(admin, "/api/v1/admin/accounts/students", tag + "50%"))
                .containsExactly(percent.getFirstName());
        assertThat(firstNames(admin, "/api/v1/admin/accounts", tag + "a_b"))
                .containsExactly(underscore.getFirstName());
        assertThat(firstNames(admin, "/api/v1/admin/accounts/students", tag))
                .containsExactlyInAnyOrder(percent.getFirstName(), plain.getFirstName(), underscore.getFirstName(),
                        noUnderscore.getFirstName());
        assertThat(firstNames(admin, "/api/v1/admin/accounts", "%")).as("a lone %% is not a match-all")
                .doesNotContain(plain.getFirstName(), noUnderscore.getFirstName());
        assertThat(firstNames(admin, "/api/v1/admin/accounts", tag + "!")).isEmpty();
    }

    @Test
    void whitelistedSortKeysOrderTheResults() throws Exception {
        Account admin = account(Role.ADMIN);
        String tag = "Srt" + UUID.randomUUID().toString().substring(0, 6);
        student(tag + "A", "Charlie");
        student(tag + "B", "Alpha");
        student(tag + "C", "Bravo");

        assertThat(lastNames(admin, tag, null, null)).as("default: first name ascending")
                .containsExactly("Charlie", "Alpha", "Bravo");
        assertThat(lastNames(admin, tag, "lastName", "asc")).containsExactly("Alpha", "Bravo", "Charlie");
        assertThat(lastNames(admin, tag, "lastName", "DESC")).containsExactly("Charlie", "Bravo", "Alpha");
        assertThat(lastNames(admin, tag, null, "desc")).containsExactly("Bravo", "Alpha", "Charlie");
    }

    private Account student(String firstName, String lastName) {
        Account account = account(Role.USER);
        account.setFirstName(firstName);
        account.setLastName(lastName);
        return accountRepository.save(account);
    }

    private List<String> firstNames(Account admin, String route, String search) throws Exception {
        return field(perform(admin, get(route).param("search", search).param("size", "100"), null), "firstName");
    }

    private List<String> lastNames(Account admin, String search, String sort, String direction) throws Exception {
        var request = get("/api/v1/admin/accounts/students").param("search", search);
        if (sort != null) {
            request.param("sort", sort);
        }
        if (direction != null) {
            request.param("direction", direction);
        }
        return field(perform(admin, request, null), "lastName");
    }

    private List<String> field(org.springframework.test.web.servlet.MvcResult result, String name) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode page = body(result);
        List<String> values = new ArrayList<>();
        page.get("content").forEach(item -> values.add(item.get(name).asText()));
        return values;
    }
}
