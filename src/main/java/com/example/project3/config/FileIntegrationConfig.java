package com.example.project3.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.annotation.InboundChannelAdapter;
import org.springframework.integration.annotation.Poller;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.config.EnableIntegration;
import org.springframework.integration.core.MessageSource;
import org.springframework.integration.file.FileReadingMessageSource;
import org.springframework.integration.file.filters.SimplePatternFileListFilter;
import org.springframework.messaging.MessageChannel;

import java.io.File;

@Configuration
@EnableIntegration
public class FileIntegrationConfig {

    // 1. Dosyaların akacağı kanal
    @Bean
    public MessageChannel fileInputChannel() {
        return new DirectChannel();
    }

    // 2. csv_uploads klasörünü dinleyen adaptör (5 saniyede bir kontrol eder)
    @Bean
    @InboundChannelAdapter(value = "fileInputChannel", poller = @Poller(fixedDelay = "5000"))
    public MessageSource<File> fileReadingMessageSource() {
        FileReadingMessageSource source = new FileReadingMessageSource();
        source.setDirectory(new File("csv_uploads")); // Dinlenecek klasör
        source.setFilter(new SimplePatternFileListFilter("*.csv")); // Sadece CSV'leri al
        return source;
    }

    // 3. Dosya bulununca tetiklenecek metod (Yönlendirme Noktası)
    @ServiceActivator(inputChannel = "fileInputChannel")
    public void processFile(File file) {
        System.out.println("Spring Integration Yeni Dosya Yakaladı: " + file.getName());

        // TODO: Burada var olan CsvJobService'ini çağırıp Job'u başlatabilirsin.
        // Örnek: csvJobService.runJob(file.getAbsolutePath());

        // İşlem bittikten sonra dosyayı .done olarak isimlendirmek veya taşımak iyi bir pratiktir.
    }
}