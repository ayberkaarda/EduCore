package com.educore.ingestion.batch;

import com.educore.config.EduCoreProperties;
import com.educore.entity.Account;
import com.educore.entity.Course;
import com.educore.entity.Role;
import com.educore.ingestion.IngestionDirectories;
import com.educore.ingestion.IngestionFence;
import com.educore.ingestion.IngestionLedger;
import com.educore.repository.AccountRepository;
import com.educore.repository.CourseRepository;
import com.educore.service.AccountCredentialService;
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
import org.springframework.batch.item.file.FlatFileParseException;
import org.springframework.batch.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.batch.item.file.separator.DefaultRecordSeparatorPolicy;
import org.springframework.batch.item.support.CompositeItemProcessor;
import org.springframework.batch.item.support.SynchronizedItemStreamReader;
import org.springframework.batch.item.validator.BeanValidatingItemProcessor;
import org.springframework.batch.item.validator.ValidationException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.task.TaskExecutor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/**
 * The two import jobs (D-06). Each has one multi-threaded, fault-tolerant chunk step:
 * <pre>
 * SynchronizedItemStreamReader(FlatFileItemReader + strict DelimitedLineTokenizer)
 *   -> BlankRowFilter -> BeanValidatingItemProcessor -> RowDeduplicator (file + database) -> entity
 *   -> PreparedRowWriter(RepositoryItemWriter.save)
 * </pre>
 * Every chunk write first verifies the run's lease ({@link IngestionFence}). Only {@link FlatFileParseException},
 * {@link ValidationException} and {@link DataIntegrityViolationException} are skipped, at most {@code educore.ingestion.skip-limit} times; anything else, or one skip more, fails the
 * job. Validation and duplicate rejections do not roll the chunk back. Every skip is recorded by
 * {@link RowSkipRecorder}. Job parameters: {@code jobLogId}, {@code importedFileId}, {@code runId} (ids only;
 * the reader resolves the run's snapshot through the job log). Parse, validation and database exceptions are
 * replaced by code-only exceptions ({@link RowParseException}, {@link InvalidRowException},
 * {@link RowConstraintException}) before Batch sees them, so failure descriptions and logs carry no CSV values.
 */
@Configuration(proxyBeanMethods = false)
public class ImportJobConfig {

    public static final String STUDENT_JOB = "importStudentJob";
    public static final String COURSE_JOB = "importCourseJob";

    @Bean
    public ThreadPoolTaskExecutor ingestionTaskExecutor(EduCoreProperties properties) {
        int threads = properties.ingestion().threads();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(threads);
        executor.setMaxPoolSize(threads);
        executor.setThreadNamePrefix("ingest-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }

    // ---- students -------------------------------------------------------------------------------------------

    @Bean
    @StepScope
    public SanitizingItemReader<StudentCsvRecord> studentCsvReader(
            @Value("#{jobParameters['jobLogId']}") Long jobLogId, IngestionLedger ledger,
            IngestionDirectories directories) {
        return synchronizedReader("studentCsvReader", snapshot(jobLogId, ledger, directories),
                new CsvLineMapper<>(StudentCsvRecord.COLUMNS, StudentCsvRecord::of, StudentCsvRecord::blankLine));
    }

    @Bean
    @StepScope
    public ItemProcessor<StudentCsvRecord, PreparedRow<Account>> studentRowProcessor(
            LocalValidatorFactoryBean validator, AccountRepository accountRepository,
            AccountCredentialService credentialService) throws Exception {
        RowDeduplicator<StudentCsvRecord> deduplicator = new RowDeduplicator<>(StudentCsvRecord::studentNumber,
                number -> accountRepository.existsByStudentNumber(number)
                        || accountRepository.findByUsername(number).isPresent());
        ItemProcessor<StudentCsvRecord, PreparedRow<Account>> toAccount = row -> {
            // Same rules as the API: the student number is the username; a random temporary password is stored
            // only as a hash and must be changed at first sign-in.
            Account account = Account.builder()
                    .firstName(row.firstName())
                    .lastName(row.lastName())
                    .studentNumber(row.studentNumber())
                    .username(row.studentNumber())
                    .role(Role.USER)
                    .build();
            credentialService.assignTemporaryPassword(account);
            return new PreparedRow<>(row.lineNumber(), row.rawLine(), account);
        };
        return composite(new BlankRowFilter<>(), validating(validator), deduplicator, toAccount);
    }

    @Bean
    public PreparedRowWriter<Account> studentRowWriter(AccountRepository accountRepository,
                                                     IngestionFence fence) {
        return new PreparedRowWriter<>(repositoryWriter(accountRepository), fence);
    }

    @Bean
    @StepScope
    public RowSkipRecorder studentSkipRecorder(IngestionLedger ledger,
                                               @Value("#{jobParameters['jobLogId']}") Long jobLogId) {
        return new RowSkipRecorder(ledger, jobLogId);
    }

    @Bean
    public Step importStudentStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
                                  EduCoreProperties properties,
                                  @Qualifier("ingestionTaskExecutor") TaskExecutor ingestionTaskExecutor,
                                  SanitizingItemReader<StudentCsvRecord> studentCsvReader,
                                  ItemProcessor<StudentCsvRecord, PreparedRow<Account>> studentRowProcessor,
                                  PreparedRowWriter<Account> studentRowWriter,
                                  RowSkipRecorder studentSkipRecorder) {
        return step("importStudentStep", jobRepository, transactionManager, properties, ingestionTaskExecutor,
                studentCsvReader, studentRowProcessor, studentRowWriter, studentSkipRecorder);
    }

    @Bean
    public Job importStudentJob(JobRepository jobRepository, Step importStudentStep) {
        return new JobBuilder(STUDENT_JOB, jobRepository).start(importStudentStep).build();
    }

    // ---- courses --------------------------------------------------------------------------------------------

    @Bean
    @StepScope
    public SanitizingItemReader<CourseCsvRecord> courseCsvReader(
            @Value("#{jobParameters['jobLogId']}") Long jobLogId, IngestionLedger ledger,
            IngestionDirectories directories) {
        return synchronizedReader("courseCsvReader", snapshot(jobLogId, ledger, directories),
                new CsvLineMapper<>(CourseCsvRecord.COLUMNS, CourseCsvRecord::of, CourseCsvRecord::blankLine));
    }

    @Bean
    @StepScope
    public ItemProcessor<CourseCsvRecord, PreparedRow<Course>> courseRowProcessor(
            LocalValidatorFactoryBean validator, CourseRepository courseRepository) throws Exception {
        RowDeduplicator<CourseCsvRecord> deduplicator = new RowDeduplicator<>(CourseCsvRecord::name,
                name -> courseRepository.findByName(name).isPresent());
        ItemProcessor<CourseCsvRecord, PreparedRow<Course>> toCourse = row -> new PreparedRow<>(row.lineNumber(),
                row.rawLine(), Course.builder().name(row.name()).term(row.term()).instructor(row.instructor()).build());
        return composite(new BlankRowFilter<>(), validating(validator), deduplicator, toCourse);
    }

    @Bean
    public PreparedRowWriter<Course> courseRowWriter(CourseRepository courseRepository,
                                                     IngestionFence fence) {
        return new PreparedRowWriter<>(repositoryWriter(courseRepository), fence);
    }

    @Bean
    @StepScope
    public RowSkipRecorder courseSkipRecorder(IngestionLedger ledger,
                                              @Value("#{jobParameters['jobLogId']}") Long jobLogId) {
        return new RowSkipRecorder(ledger, jobLogId);
    }

    @Bean
    public Step importCourseStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
                                 EduCoreProperties properties,
                                 @Qualifier("ingestionTaskExecutor") TaskExecutor ingestionTaskExecutor,
                                 SanitizingItemReader<CourseCsvRecord> courseCsvReader,
                                 ItemProcessor<CourseCsvRecord, PreparedRow<Course>> courseRowProcessor,
                                 PreparedRowWriter<Course> courseRowWriter,
                                 RowSkipRecorder courseSkipRecorder) {
        return step("importCourseStep", jobRepository, transactionManager, properties, ingestionTaskExecutor,
                courseCsvReader, courseRowProcessor, courseRowWriter, courseSkipRecorder);
    }

    @Bean
    public Job importCourseJob(JobRepository jobRepository, Step importCourseStep) {
        return new JobBuilder(COURSE_JOB, jobRepository).start(importCourseStep).build();
    }

    // ---- shared ---------------------------------------------------------------------------------------------

    /** The run's private snapshot (the job parameters carry only ids, never a file name). */
    private static Path snapshot(Long jobLogId, IngestionLedger ledger, IngestionDirectories directories) {
        return directories.processing().resolve(ledger.snapshotName(jobLogId));
    }

    private static <T extends CsvRow> SanitizingItemReader<T> synchronizedReader(
            String name, Path file, CsvLineMapper<T> lineMapper) {
        FlatFileItemReader<T> reader = new FlatFileItemReaderBuilder<T>()
                .name(name)
                .resource(new FileSystemResource(file))
                .encoding(StandardCharsets.UTF_8.name())
                .linesToSkip(1)
                .strict(true)
                // A multi-threaded step cannot restart from a saved position.
                .saveState(false)
                // Quoted fields may span lines; NUL never occurs (rejected before launch), so there is no
                // line-continuation character.
                .recordSeparatorPolicy(new DefaultRecordSeparatorPolicy("\"", "\u0000"))
                .lineMapper(lineMapper)
                .build();
        SynchronizedItemStreamReader<T> synchronizedReader = new SynchronizedItemStreamReader<>();
        synchronizedReader.setDelegate(reader);
        return new SanitizingItemReader<>(synchronizedReader);
    }

    /** Bean Validation of the record; failures carry field names only ({@link InvalidRowException}). */
    private static <T> ItemProcessor<T, T> validating(LocalValidatorFactoryBean validator) throws Exception {
        BeanValidatingItemProcessor<T> processor = new BeanValidatingItemProcessor<>(validator);
        processor.setFilter(false);
        processor.afterPropertiesSet();
        return InvalidRowException.sanitizing(processor);
    }

    @SafeVarargs
    private static <I, O> ItemProcessor<I, O> composite(ItemProcessor<?, ?>... delegates) throws Exception {
        CompositeItemProcessor<I, O> composite = new CompositeItemProcessor<>();
        composite.setDelegates(List.of(delegates));
        composite.afterPropertiesSet();
        return composite;
    }

    private static <E> RepositoryItemWriter<E> repositoryWriter(
            org.springframework.data.repository.CrudRepository<E, ?> repository) {
        return new RepositoryItemWriterBuilder<E>().repository(repository).methodName("save").build();
    }

    @SuppressWarnings({"deprecation", "removal"})
    private static <I extends CsvRow, E> Step step(String name, JobRepository jobRepository,
                                                   PlatformTransactionManager transactionManager,
                                                   EduCoreProperties properties, TaskExecutor taskExecutor,
                                                   SanitizingItemReader<I> reader,
                                                   ItemProcessor<I, PreparedRow<E>> processor,
                                                   PreparedRowWriter<E> writer, RowSkipRecorder skipRecorder) {
        EduCoreProperties.Ingestion ingestion = properties.ingestion();
        return new StepBuilder(name, jobRepository)
                .<I, PreparedRow<E>>chunk(ingestion.chunkSize(), transactionManager)
                .reader(reader)
                .processor(processor)
                .writer(writer)
                .faultTolerant()
                .skip(FlatFileParseException.class)
                .skip(ValidationException.class)
                .skip(DataIntegrityViolationException.class)
                .skipLimit(ingestion.skipLimit())
                .noRollback(ValidationException.class)
                .listener(skipRecorder)
                .taskExecutor(taskExecutor)
                // Concurrent chunks; the executor has the same number of threads.
                .throttleLimit(ingestion.threads())
                .build();
    }
}
