package com.educore.service;

import com.educore.config.BatchConfig;
import com.educore.config.JobTracker;
import com.educore.dto.StudentCsvRecord;
import com.educore.entity.Account;
import com.educore.repository.AccountRepository;
import com.educore.repository.JobLogRepository;
import com.educore.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Both CSV importers (the Spring Batch student processor and {@link StudentMultiThreadService}) give every
 * imported account its own random temporary password, stored only as a hash, with {@code mustChangePassword}
 * set. The former shared defaults no longer match any imported account.
 */
class CsvImportPasswordIT extends AbstractIntegrationTest {

    /** The removed shared defaults; an imported account must never accept them. */
    private static final List<String> FORMER_DEFAULTS = List.of("REMOVED-DB-PASSWORD", "REMOVED-DB-PASSWORD56");

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private JobLogRepository jobLogRepository;

    @Autowired
    private AccountCredentialService accountCredentialService;

    @Autowired
    private JobTracker jobTracker;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private StudentMultiThreadService studentMultiThreadService;

    @TempDir
    Path tempDir;

    private final List<String> studentNumbers = new ArrayList<>();
    private String importedFileName;

    @AfterEach
    void cleanUp() {
        studentNumbers.forEach(number -> accountRepository.findByStudentNumber(number).ifPresent(accountRepository::delete));
        if (importedFileName != null) {
            jobLogRepository.findAll().stream()
                    .filter(log -> importedFileName.equals(log.getFileName()))
                    .forEach(jobLogRepository::delete);
        }
    }

    @Test
    void batchStudentProcessorAssignsARandomHashedTemporaryPassword() throws Exception {
        ItemProcessor<StudentCsvRecord, Account> processor = new BatchConfig()
                .studentProcessor(accountRepository, accountCredentialService, jobTracker, -1L);
        String firstNumber = uniqueStudentNumber();
        String secondNumber = uniqueStudentNumber();

        Account first = accountRepository.save(processor.process(new StudentCsvRecord("Batch", "One", firstNumber)));
        Account second = accountRepository.save(processor.process(new StudentCsvRecord("Batch", "Two", secondNumber)));
        jobTracker.clear(-1L);

        assertImportedCredentials(accountRepository.findById(first.getId()).orElseThrow(),
                accountRepository.findById(second.getId()).orElseThrow());
    }

    @Test
    void multiThreadImporterAssignsARandomHashedTemporaryPassword() throws Exception {
        String firstNumber = uniqueStudentNumber();
        String secondNumber = uniqueStudentNumber();
        importedFileName = "students-" + firstNumber + ".csv";
        Path csv = tempDir.resolve(importedFileName);
        Files.writeString(csv, "firstName,lastName,studentNumber\n"
                + "Thread,One," + firstNumber + "\n"
                + "Thread,Two," + secondNumber + "\n", StandardCharsets.UTF_8);

        assertThat(studentMultiThreadService.processFileWithThreads(csv.toFile())).isTrue();

        assertImportedCredentials(accountRepository.findByStudentNumber(firstNumber).orElseThrow(),
                accountRepository.findByStudentNumber(secondNumber).orElseThrow());
    }

    private void assertImportedCredentials(Account first, Account second) {
        for (Account account : List.of(first, second)) {
            assertThat(account.getPassword()).startsWith("{bcrypt}$2a$12$");
            assertThat(account.isMustChangePassword()).isTrue();
            for (String former : FORMER_DEFAULTS) {
                assertThat(passwordEncoder.matches(former, account.getPassword())).isFalse();
            }
        }
        assertThat(first.getPassword()).isNotEqualTo(second.getPassword());
    }

    /** Synthetic student numbers in the obviously fake 97xxxxxx range. */
    private String uniqueStudentNumber() {
        String number = "97" + ThreadLocalRandom.current().nextInt(100_000, 1_000_000);
        studentNumbers.add(number);
        return number;
    }
}
