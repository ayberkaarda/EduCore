package com.educore;

import com.educore.support.AbstractIntegrationTest;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The application context starts against a fresh PostgreSQL: Flyway applies V1, V2, V4, V10 and the repeatable
 * dev/test seed, and Hibernate schema validation ({@code ddl-auto=validate}) accepts the migrated schema.
 */
@AutoConfigureMockMvc
class EduCoreApplicationIT extends AbstractIntegrationTest {

    /** Local demo password of the seeded dev/test accounts (dev and test profiles only). */
    private static final String DEMO_PASSWORD = "REMOVED-DB-PASSWORD";

    @Autowired
    private Flyway flyway;

    @Autowired
    private Environment environment;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void contextLoadsWithHibernateSchemaValidation() {
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(environment.getProperty("spring.jpa.show-sql")).isEqualTo("false");
        assertThat(environment.getProperty("spring.jpa.open-in-view")).isEqualTo("false");
    }

    @Test
    void flywayAppliedVersionedMigrationsAndRepeatableDevSeed() {
        MigrationInfo[] applied = flyway.info().applied();

        List<String> versions = Arrays.stream(applied).filter(info -> info.getVersion() != null)
                .map(info -> info.getVersion().getVersion()).toList();
        List<String> repeatables = Arrays.stream(applied).filter(info -> info.getVersion() == null)
                .map(MigrationInfo::getDescription).toList();
        assertThat(versions).containsExactly("1", "2", "4", "10", "11", "20");
        assertThat(repeatables).containsExactly("dev seed");
        assertThat(applied).allSatisfy(info -> assertThat(info.getState()).isEqualTo(MigrationState.SUCCESS));
        assertThat(flyway.info().pending()).isEmpty();
    }

    @Test
    void seededDemoAccountsCanLogIn() throws Exception {
        for (String username : List.of("admin", "ayberk", "ali")) {
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + username + "\",\"password\":\"" + DEMO_PASSWORD + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accessToken").isNotEmpty());
        }
    }

    @Test
    void wrongPasswordIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized());
    }
}
