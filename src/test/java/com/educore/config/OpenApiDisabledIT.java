package com.educore.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.educore.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Outside the {@code dev} profile neither the OpenAPI document nor Swagger UI exists: an anonymous request is
 * refused by the main security chain (no springdoc chain is registered) and no document is generated.
 */
@AutoConfigureMockMvc
class OpenApiDisabledIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void openApiDocumentAndSwaggerUiAreNotServed() throws Exception {
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/v3/api-docs.yaml")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/swagger-ui.html")).andExpect(status().isUnauthorized());
    }
}
