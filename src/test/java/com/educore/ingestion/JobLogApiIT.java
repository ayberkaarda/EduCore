package com.educore.ingestion;

import com.educore.entity.Account;
import com.educore.entity.JobLog;
import com.educore.entity.JobLogStatus;
import com.educore.entity.Role;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** {@code GET /api/v1/admin/job-logs} filters, sort whitelist and paging, and {@code /{id}/entries}. */
class JobLogApiIT extends IngestionIntegrationSupport {

    @Test
    void filtersSortsAndPagesJobLogsAndListsTheirEntries() throws Exception {
        Account admin = account(Role.ADMIN);
        String token = token();
        String dup = number();
        JobLog succeeded = importNow(staged("b-ok-" + token + ".csv", STUDENT_HEADER + "Ali,Kaya," + number() + "\n"));
        JobLog partial = importNow(staged("a-partial-" + token + ".csv", STUDENT_HEADER
                + "Ali,Kaya," + dup + "\n" + "Can,Ak," + dup + "\n"));
        JobLog failed = importNow(staged("c-failed-" + token + ".csv", "bad header\n1\n"));

        JsonNode partialOnly = body(perform(admin, get("/api/v1/admin/job-logs")
                .param("file", token.toUpperCase()).param("status", "PARTIAL"), null));
        assertThat(partialOnly.get("totalElements").asInt()).isEqualTo(1);
        assertThat(partialOnly.get("content").get(0).get("id").asLong()).isEqualTo(partial.getId());
        assertThat(partialOnly.get("content").get(0).get("failedRecords").asInt()).isEqualTo(1);

        JsonNode byName = body(perform(admin, get("/api/v1/admin/job-logs").param("file", token)
                .param("sort", "fileName").param("direction", "asc").param("size", "2"), null));
        assertThat(byName.get("totalElements").asInt()).isEqualTo(3);
        assertThat(byName.get("totalPages").asInt()).isEqualTo(2);
        assertThat(byName.get("content")).extracting(node -> node.get("id").asLong())
                .containsExactly(partial.getId(), succeeded.getId());

        JsonNode newestFirst = body(perform(admin, get("/api/v1/admin/job-logs").param("file", token), null));
        assertThat(newestFirst.get("content").get(0).get("id").asLong()).isEqualTo(failed.getId());
        assertThat(newestFirst.get("content").get(0).get("reason").asText()).isEqualTo("INVALID_HEADER");

        String future = Instant.now().plus(1, ChronoUnit.HOURS).toString();
        assertThat(body(perform(admin, get("/api/v1/admin/job-logs").param("file", token).param("from", future), null))
                .get("totalElements").asInt()).isZero();
        String past = Instant.now().minus(1, ChronoUnit.HOURS).toString();
        assertThat(body(perform(admin, get("/api/v1/admin/job-logs").param("file", token).param("from", past)
                .param("to", future), null)).get("totalElements").asInt()).isEqualTo(3);

        JsonNode entries = body(perform(admin, get("/api/v1/admin/job-logs/" + partial.getId() + "/entries"), null));
        assertThat(entries.get("content")).singleElement().satisfies(entry -> {
            assertThat(entry.get("reason").asText()).isEqualTo("DUPLICATE_IN_FILE");
            assertThat(entry.get("rawMasked").asText()).doesNotContain(dup);
        });
    }

    @Test
    void invalidQueriesAreRejected() throws Exception {
        Account admin = account(Role.ADMIN);

        assertProblem(perform(admin, get("/api/v1/admin/job-logs").param("sort", "password"), null), 400, "sort/invalid");
        assertProblem(perform(admin, get("/api/v1/admin/job-logs").param("direction", "up"), null), 400, "sort/invalid");
        assertProblem(perform(admin, get("/api/v1/admin/job-logs").param("size", "101"), null), 400, "request/invalid");
        assertProblem(perform(admin, get("/api/v1/admin/job-logs").param("status", "SUCCESS"), null), 400,
                "request/invalid");
        assertProblem(perform(admin, get("/api/v1/admin/job-logs").param("from", "yesterday"), null), 400,
                "request/invalid");
        MvcResult reversed = perform(admin, get("/api/v1/admin/job-logs").param("from", "2026-10-02T00:00:00Z")
                .param("to", "2026-10-01T00:00:00Z"), null);
        assertProblem(reversed, 400, "request/invalid");
        assertThat(body(reversed).get("errors").get(0).get("field").asText()).isEqualTo("from");
        assertProblem(perform(admin, get("/api/v1/admin/job-logs/999999999/entries"), null), 404, "job-log/not-found");
        assertProblem(perform(admin, get("/api/v1/admin/job-logs/0/entries"), null), 400, "request/invalid");
    }

    private void assertProblem(MvcResult result, int status, String code) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(status);
        assertThat(body(result).get("code").asText()).isEqualTo(code);
    }
}
