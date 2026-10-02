package com.educore.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * OpenAPI description of the REST API (springdoc-openapi).
 * <p>
 * The document ({@code /v3/api-docs}, {@code /v3/api-docs.yaml}) and Swagger UI ({@code /swagger-ui.html}) are
 * disabled by default ({@code springdoc-defaults.properties}, lowest precedence) and enabled only by the
 * {@code dev} profile ({@code application-dev.yml}). The committed export {@code docs/api/openapi.yaml} is kept
 * in sync by {@code OpenApiDocumentIT}.
 */
@Configuration(proxyBeanMethods = false)
@PropertySource("classpath:springdoc-defaults.properties")
public class OpenApiConfig {

    static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    OpenAPI educoreOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("EduCore API")
                        .version("v1")
                        .description("Course catalog, enrollment, account administration, CSV ingestion and webhooks. "
                                + "Errors are RFC 9457 problem details (application/problem+json). Authenticate with "
                                + "POST /api/v1/auth/login and send the access token as 'Authorization: Bearer <token>'."))
                // A relative server keeps the document independent of the host it was exported from.
                .servers(List.of(new Server().url("/").description("Same origin as the web application")))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }

    /**
     * Lets the document and the UI load without a token while they are enabled (dev only). The chain matches only
     * the springdoc paths, so every API route keeps the main chain's rules.
     */
    @Bean
    @Order(0)
    @ConditionalOnProperty(name = "springdoc.api-docs.enabled", havingValue = "true")
    SecurityFilterChain openApiDocsSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher("/v3/api-docs", "/v3/api-docs/**", "/v3/api-docs.yaml",
                        "/swagger-ui.html", "/swagger-ui/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(csrf -> csrf.disable())
                .build();
    }
}
