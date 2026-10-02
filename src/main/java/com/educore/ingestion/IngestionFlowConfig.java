package com.educore.ingestion;

import com.educore.config.EduCoreProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.dsl.Pollers;
import org.springframework.integration.file.FileReadingMessageSource;
import org.springframework.integration.file.filters.ChainFileListFilter;
import org.springframework.integration.file.filters.FileSystemPersistentAcceptOnceFileListFilter;
import org.springframework.integration.file.filters.LastModifiedFileListFilter;
import org.springframework.integration.file.filters.RegexPatternFileListFilter;
import org.springframework.integration.jdbc.metadata.JdbcMetadataStore;

import javax.sql.DataSource;
import java.io.File;

/**
 * Spring Integration inbound adapter on {@code <base-dir>/inbox}. Files pass a chain of filters, each
 * seeing only what the previous one let through:
 * <ol>
 *   <li>{@code *.csv} that is not a dotfile ({@code .gitkeep} and hidden temporary files are never taken);</li>
 *   <li>unchanged for at least {@code educore.ingestion.stable-after} (default 2 s), so half-written files
 *       are left alone until the writer is done;</li>
 *   <li>accept once per path and modification time, remembered in a {@link JdbcMetadataStore} (table
 *       {@code INT_METADATA_STORE}), so the decision survives restarts.</li>
 * </ol>
 * The poller runs every {@code educore.ingestion.poll-interval} and hands each file to
 * {@link IngestionService#ingest}; {@code educore.ingestion.poller-enabled=false} keeps it stopped.
 */
@Configuration(proxyBeanMethods = false)
public class IngestionFlowConfig {

    static final String METADATA_REGION = "educore-ingestion";
    static final String ACCEPT_ONCE_PREFIX = "inbox:";
    public static final String INBOX_ADAPTER_ID = "ingestionInboxAdapter";
    /** Same rule as {@link IngestionDirectories#isInboxCandidate}: {@code *.csv} without a leading dot. */
    static final String INBOX_PATTERN = "^[^.].*\\.csv$";

    @Bean
    public JdbcMetadataStore ingestionMetadataStore(DataSource dataSource) {
        JdbcMetadataStore store = new JdbcMetadataStore(dataSource);
        store.setRegion(METADATA_REGION);
        return store;
    }

    @Bean
    public FileSystemPersistentAcceptOnceFileListFilter ingestionAcceptOnceFilter(JdbcMetadataStore ingestionMetadataStore) {
        return new FileSystemPersistentAcceptOnceFileListFilter(ingestionMetadataStore, ACCEPT_ONCE_PREFIX);
    }

    @Bean
    public FileReadingMessageSource ingestionInboxSource(IngestionDirectories directories, EduCoreProperties properties,
                                                         FileSystemPersistentAcceptOnceFileListFilter ingestionAcceptOnceFilter) {
        LastModifiedFileListFilter stable = new LastModifiedFileListFilter();
        stable.setAge(properties.ingestion().stableAfter());
        ChainFileListFilter<File> filters = new ChainFileListFilter<>();
        // *.csv, but never a dotfile (.gitkeep, a client's hidden temporary file): see IngestionDirectories.isForeign.
        filters.addFilter(new RegexPatternFileListFilter(INBOX_PATTERN));
        filters.addFilter(stable);
        filters.addFilter(ingestionAcceptOnceFilter);
        FileReadingMessageSource source = new FileReadingMessageSource();
        source.setDirectory(directories.inbox().toFile());
        source.setAutoCreateDirectory(true);
        source.setFilter(filters);
        return source;
    }

    @Bean
    public IntegrationFlow ingestionFlow(FileReadingMessageSource ingestionInboxSource, IngestionService ingestionService,
                                         EduCoreProperties properties) {
        EduCoreProperties.Ingestion ingestion = properties.ingestion();
        return IntegrationFlow.from(ingestionInboxSource, adapter -> adapter
                        .id(INBOX_ADAPTER_ID)
                        .autoStartup(ingestion.pollerEnabled())
                        .poller(Pollers.fixedDelay(ingestion.pollInterval()).maxMessagesPerPoll(10)))
                .handle(File.class, (file, headers) -> {
                    ingestionService.ingest(file.toPath());
                    return null;
                })
                .get();
    }
}
