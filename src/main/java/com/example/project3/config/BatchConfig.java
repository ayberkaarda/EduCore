package com.example.project3.config;

import com.example.project3.dto.StudentCsvRecord;
import com.example.project3.entity.Account;
import com.example.project3.entity.Role;
import com.example.project3.repository.AccountRepository;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.data.RepositoryItemWriter;
import org.springframework.batch.item.data.builder.RepositoryItemWriterBuilder;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class BatchConfig {

    // 1. READER: CSV'yi Okur
    @Bean
    @StepScope
    public com.example.project3.config.FlatFileItemReader<StudentCsvRecord> reader(@Value("#{jobParameters['filePath']}") String filePath) {
        return new FlatFileItemReaderBuilder<StudentCsvRecord>()
                .name("studentItemReader")
                .resource(new FileSystemResource(filePath))
                .linesToSkip(1) // İlk satırı (Başlıkları) atla
                .delimited()
                .names("firstName", "lastName", "studentNumber")
                .targetType(StudentCsvRecord.class)
                .build();
    }

    // 2. PROCESSOR: İş Kuralları (Aynı numara varsa atla)
    @Bean
    public ItemProcessor<StudentCsvRecord, Account> processor(AccountRepository accountRepository, PasswordEncoder passwordEncoder) {
        return record -> {
            // Numarayı kontrol et, varsa null dön (Spring Batch null dönenleri "Filtrelendi/Hata" sayar ve DB'ye yazmaz)
            if (accountRepository.findByStudentNumber(record.getStudentNumber()).isPresent()) {
                return null;
            }

            String generatedUsername = record.getFirstName().toLowerCase() + System.currentTimeMillis() % 1000;
            return Account.builder()
                    .firstName(record.getFirstName())
                    .lastName(record.getLastName())
                    .studentNumber(record.getStudentNumber())
                    .username(generatedUsername)
                    .password(passwordEncoder.encode("1234"))
                    .role(Role.USER)
                    .build();
        };
    }

    // 3. WRITER: Veritabanına Yazar
    @Bean
    public RepositoryItemWriter<Account> writer(AccountRepository accountRepository) {
        return new RepositoryItemWriterBuilder<Account>()
                .repository(accountRepository)
                .methodName("save")
                .build();
    }

    // 4. STEP: Reader, Processor ve Writer'ı birleştirir
    @Bean
    @SuppressWarnings("deprecation") // DÜZELTME: IDE'nin verdiği 'chunk deprecated' sarı uyarısını susturur.
    public Step processCsvStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
                               FlatFileItemReader<StudentCsvRecord> reader,
                               ItemProcessor<StudentCsvRecord, Account> processor,
                               RepositoryItemWriter<Account> writer) {
        return new StepBuilder("processCsvStep", jobRepository)
                .<StudentCsvRecord, Account>chunk(10, transactionManager)
                .reader(reader)
                .processor(processor)
                .writer(writer)
                .faultTolerant()
                .skip(Exception.class)
                .skipLimit(100)
                .build();
    }

    // 5. JOB: Tetiklenecek Ana Görev
    @Bean
    public Job importStudentJob(JobRepository jobRepository, Step processCsvStep) {
        return new JobBuilder("importStudentJob", jobRepository)
                .start(processCsvStep)
                .build();
    }
}