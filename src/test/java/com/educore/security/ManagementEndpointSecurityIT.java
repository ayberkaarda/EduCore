package com.educore.security;

import com.educore.repository.AccountRepository;
import com.educore.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** Actuator lives on the management port: health is open, every other endpoint needs an ADMIN token. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// Spring Boot disables metrics export (and therefore the Prometheus endpoint) in tests unless asked.
@AutoConfigureObservability(tracing = false)
class ManagementEndpointSecurityIT extends AbstractIntegrationTest {

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalManagementPort
    private int managementPort;

    @LocalServerPort
    private int serverPort;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private AccountRepository accountRepository;

    private HttpResponse<String> get(int port, String path, String username) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (username != null) {
            String token = jwtService.issue(
                    AuthenticatedUser.of(accountRepository.findByUsername(username).orElseThrow())).token();
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void managementPortDiffersFromApplicationPort() {
        assertThat(managementPort).isPositive().isNotEqualTo(serverPort);
    }

    @Test
    void healthIsOpen() throws Exception {
        HttpResponse<String> response = get(managementPort, "/actuator/health", null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }

    @Test
    void otherEndpointsRejectAnonymousAndNonAdminCallers() throws Exception {
        for (String path : new String[]{"/actuator/info", "/actuator/metrics", "/actuator/prometheus"}) {
            assertThat(get(managementPort, path, null).statusCode()).as("anonymous %s", path).isEqualTo(401);
            assertThat(get(managementPort, path, "ayberk").statusCode()).as("USER %s", path).isEqualTo(403);
        }
    }

    @Test
    void adminCanReadMetricsAndPrometheus() throws Exception {
        assertThat(get(managementPort, "/actuator/info", "admin").statusCode()).isEqualTo(200);
        assertThat(get(managementPort, "/actuator/metrics", "admin").statusCode()).isEqualTo(200);
        HttpResponse<String> prometheus = get(managementPort, "/actuator/prometheus", "admin");
        assertThat(prometheus.statusCode()).isEqualTo(200);
        assertThat(prometheus.body()).contains("jvm_memory_used_bytes");
    }

    @Test
    void actuatorIsNotServedOnTheApplicationPort() throws Exception {
        assertThat(get(serverPort, "/actuator/health", null).statusCode()).isNotEqualTo(200);
        assertThat(get(serverPort, "/actuator/metrics", "admin").statusCode()).isEqualTo(404);
    }

    /** R-03: an ADMIN that must change its password has no role on the management port either. */
    @Test
    void anAdminThatMustChangeThePasswordIsRefusedWithTheScopeCode() throws Exception {
        com.educore.entity.Account admin = accountRepository.save(com.educore.entity.Account.builder()
                .username("mgmt-must-change-" + java.util.UUID.randomUUID())
                .password("{bcrypt}not-used-by-this-test")
                .firstName("Management")
                .role(com.educore.entity.Role.ADMIN)
                .mustChangePassword(true)
                .build());
        try {
            String token = jwtService.issue(AuthenticatedUser.of(admin)).token();
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + managementPort
                    + "/actuator/metrics")).header("Authorization", "Bearer " + token).GET().build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(403);
            assertThat(response.body()).contains("account/password-change-required");
        } finally {
            accountRepository.deleteById(admin.getId());
        }
    }
}
