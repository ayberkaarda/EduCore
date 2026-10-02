package com.educore.ingestion;

import com.educore.entity.Account;
import com.educore.entity.Role;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The upload endpoint behind the real servlet container (Tomcat multipart parsing to disk, the
 * {@code spring.servlet.multipart.*} limits): a valid upload is accepted, a part above
 * {@code max-file-size} answers 413 {@code request/payload-too-large}, and the container's multipart temporary
 * files are gone after each request.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext
class ImportUploadContainerIT extends IngestionIntegrationSupport {

    private static final Path MULTIPART_TEMP = createTemp();

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void multipartLocation(DynamicPropertyRegistry registry) {
        registry.add("spring.servlet.multipart.location", MULTIPART_TEMP::toString);
        registry.add("spring.servlet.multipart.file-size-threshold", () -> "0");
    }

    private static Path createTemp() {
        try {
            return Files.createTempDirectory("educore-multipart-it-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private HttpResponse<String> upload(Account caller, String fileName, byte[] content) throws Exception {
        String boundary = "----educore" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + fileName
                + "\"\r\nContent-Type: text/csv\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(content);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/admin/imports"))
                .header("Authorization", bearer(caller))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void realMultipartUploadsAreLimitedAndLeaveNoTemporaryFiles() throws Exception {
        Account admin = account(Role.ADMIN);
        String token = token();

        HttpResponse<String> accepted = upload(admin, "courses-" + token + ".csv",
                (COURSE_HEADER + courseName("container") + ",2026/1,I\n").getBytes(StandardCharsets.UTF_8));
        byte[] large = new byte[5 * 1024 * 1024 + 512 * 1024];
        java.util.Arrays.fill(large, (byte) 'a');
        HttpResponse<String> tooLarge = upload(admin, "large-" + token + ".csv", large);

        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(202);
        assertThat(json.readTree(accepted.body()).get("kind").asText()).isEqualTo("COURSES");
        assertThat(tooLarge.statusCode()).as(tooLarge.body()).isEqualTo(413);
        JsonNode problem = json.readTree(tooLarge.body());
        assertThat(problem.get("code").asText()).isEqualTo("request/payload-too-large");
        assertThat(filesContaining(directories.inbox(), "large-" + token)).isEmpty();
        try (Stream<Path> left = Files.walk(MULTIPART_TEMP)) {
            assertThat(left.filter(Files::isRegularFile).toList()).isEmpty();
        }
        awaitClosedJobLog(token, java.time.Duration.ofSeconds(30));
    }
}
