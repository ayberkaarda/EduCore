package com.educore.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.educore.support.AbstractIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Keeps {@code docs/api/openapi.yaml} identical to the document springdoc generates from the controllers.
 * <p>
 * The test enables the document the way the {@code dev} profile does and compares {@code /v3/api-docs.yaml} with
 * the committed file. After an API change, regenerate the file with
 * {@code ./mvnw verify -Dit.test=OpenApiDocumentIT -Dopenapi.write=true} and commit it.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "springdoc.api-docs.enabled=true")
class OpenApiDocumentIT extends AbstractIntegrationTest {

    private static final Path COMMITTED = Path.of("docs", "api", "openapi.yaml");

    @Autowired
    private MockMvc mockMvc;

    @Test
    void committedOpenApiDocumentMatchesTheGeneratedOne() throws Exception {
        String generated = mockMvc.perform(get("/v3/api-docs.yaml"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String normalized = generated.replace("\r\n", "\n");

        if (Boolean.getBoolean("openapi.write")) {
            Files.createDirectories(COMMITTED.getParent());
            Files.writeString(COMMITTED, normalized, StandardCharsets.UTF_8);
        }

        assertThat(normalized).contains("openapi: 3.", "/api/v1/auth/login", "bearerAuth");
        assertThat(Files.exists(COMMITTED))
                .as("%s is missing; generate it with -Dopenapi.write=true", COMMITTED)
                .isTrue();
        String committed = Files.readString(COMMITTED, StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertThat(committed)
                .as("%s is out of date; regenerate it with -Dopenapi.write=true", COMMITTED)
                .isEqualTo(normalized);
    }

}
