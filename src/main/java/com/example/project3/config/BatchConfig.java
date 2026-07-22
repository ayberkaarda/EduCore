package com.example.project3.config;

import com.example.project3.dto.CourseCsvRecord;
import com.example.project3.dto.StudentCsvRecord;
import com.example.project3.entity.Account;
import com.example.project3.entity.Course;
import com.example.project3.entity.Role;
import com.example.project3.repository.AccountRepository;
import com.example.project3.repository.CourseRepository;
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

    // ==========================================
    // 1. ÖĞRENCİ (STUDENT) BATCH YAPILANDIRMASI
    // ==========================================

    @Bean
    @StepScope
    public FlatFileItemReader<StudentCsvRecord> studentReader(@Value("#{jobParameters['filePath']}") String filePath) {
        return new FlatFileItemReaderBuilder<StudentCsvRecord>()
                .name("studentItemReader")
                .resource(new FileSystemResource(filePath))
                .linesToSkip(1)
                .delimited()
                .names("firstName", "lastName", "studentNumber")
                .targetType(StudentCsvRecord.class)
                .build();
    }

    @Bean
    @StepScope
    public ItemProcessor<StudentCsvRecord, Account> studentProcessor(
            AccountRepository accountRepository,
            PasswordEncoder passwordEncoder,
            JobTracker jobTracker,
            @Value("#{stepExecution.jobExecution.jobId}") Long jobId) {
        return record -> {
            if (accountRepository.findByStudentNumber(record.getStudentNumber()).isPresent()) {
                jobTracker.addLog(jobId, "FAILED", record.getFirstName() + " " + record.getLastName() + " - Failed: Student number (" + record.getStudentNumber() + ") already exists.");
                return null;
            }
            jobTracker.addLog(jobId, "SUCCESS", record.getFirstName() + " " + record.getLastName() + " - Successfully added.");
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

    @Bean
    public RepositoryItemWriter<Account> studentWriter(AccountRepository accountRepository) {
        return new RepositoryItemWriterBuilder<Account>()
                .repository(accountRepository)
                .methodName("save")
                .build();
    }

    @Bean
    @SuppressWarnings("deprecation")
    public Step processStudentCsvStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
                                      FlatFileItemReader<StudentCsvRecord> studentReader,
                                      ItemProcessor<StudentCsvRecord, Account> studentProcessor,
                                      RepositoryItemWriter<Account> studentWriter) {
        return new StepBuilder("processStudentCsvStep", jobRepository)
                .<StudentCsvRecord, Account>chunk(10, transactionManager)
                .reader(studentReader)
                .processor(studentProcessor)
                .writer(studentWriter)
                .faultTolerant()
                .skip(Exception.class)
                .skipLimit(150)
                .build();
    }

    @Bean
    public Job importStudentJob(JobRepository jobRepository, Step processStudentCsvStep) {
        return new JobBuilder("importStudentJob", jobRepository)
                .start(processStudentCsvStep)
                .build();
    }

    // ==========================================
    // 2. DERS (COURSE) BATCH YAPILANDIRMASI
    // ==========================================

    @Bean
    @StepScope
    public FlatFileItemReader<CourseCsvRecord> courseReader(@Value("#{jobParameters['filePath']}") String filePath) {
        return new FlatFileItemReaderBuilder<CourseCsvRecord>()
                .name("courseItemReader")
                .resource(new FileSystemResource(filePath))
                .linesToSkip(1)
                .delimited()
                .names("name", "term", "instructor")
                .targetType(CourseCsvRecord.class)
                .build();
    }

    @Bean
    @StepScope
    public ItemProcessor<CourseCsvRecord, Course> courseProcessor(
            CourseRepository courseRepo,
            JobTracker jobTracker,
            @Value("#{stepExecution.jobExecution.jobId}") Long jobId) {
        return record -> {
            if (courseRepo.findByName(record.getName()).isPresent()) {
                jobTracker.addLog(jobId, "FAILED", "Course - Failed: Course name ('" + record.getName() + "') already exists.");
                return null;
            }
            jobTracker.addLog(jobId, "SUCCESS", "Course - '" + record.getName() + "' successfully added.");
            return Course.builder()
                    .name(record.getName())
                    .term(record.getTerm())
                    .instructor(record.getInstructor())
                    .build();
        };
    }

    @Bean
    public RepositoryItemWriter<Course> courseWriter(CourseRepository courseRepo) {
        return new RepositoryItemWriterBuilder<Course>()
                .repository(courseRepo)
                .methodName("save")
                .build();
    }

    @Bean
    @SuppressWarnings("deprecation")
    public Step processCourseCsvStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
                                     FlatFileItemReader<CourseCsvRecord> courseReader,
                                     ItemProcessor<CourseCsvRecord, Course> courseProcessor,
                                     RepositoryItemWriter<Course> courseWriter) {
        return new StepBuilder("processCourseCsvStep", jobRepository)
                .<CourseCsvRecord, Course>chunk(10, transactionManager)
                .reader(courseReader)
                .processor(courseProcessor)
                .writer(courseWriter)
                .faultTolerant()
                .skip(Exception.class)
                .skipLimit(100)
                .build();
    }

    @Bean
    public Job importCourseJob(JobRepository jobRepository, Step processCourseCsvStep) {
        return new JobBuilder("importCourseJob", jobRepository)
                .start(processCourseCsvStep)
                .build();
    }
}