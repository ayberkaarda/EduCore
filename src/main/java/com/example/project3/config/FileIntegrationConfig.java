package com.example.project3.config;

import com.example.project3.service.CsvJobService;
import com.example.project3.service.StudentMultiThreadService; // 1. YENİ SERVİS EKLENDİ
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.annotation.InboundChannelAdapter;
import org.springframework.integration.annotation.Poller;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.config.EnableIntegration;
import org.springframework.integration.core.MessageSource;
import org.springframework.integration.file.FileReadingMessageSource;
import org.springframework.integration.file.filters.AcceptOnceFileListFilter;
import org.springframework.integration.file.filters.CompositeFileListFilter;
import org.springframework.integration.file.filters.SimplePatternFileListFilter;
import org.springframework.messaging.MessageChannel;

import java.io.File;

@Configuration
@EnableIntegration
public class FileIntegrationConfig {

    @Autowired
    private CsvJobService csvJobService;

    @Autowired
    private StudentMultiThreadService studentMultiThreadService; // 2. YENİ SERVİS INJECT EDİLDİ

    // 1. Dosyaların akacağı kanal
    @Bean
    public MessageChannel fileInputChannel() {
        return new DirectChannel();
    }

    // 2. csv_uploads klasörünü dinleyen adaptör
    @Bean
    @InboundChannelAdapter(value = "fileInputChannel", poller = @Poller(fixedDelay = "5000"))
    public MessageSource<File> fileReadingMessageSource() {
        FileReadingMessageSource source = new FileReadingMessageSource();
        source.setDirectory(new File("csv_uploads"));

        // KALKAN: Sadece CSV'leri al ve "aynı dosyayı yalnızca 1 kez" oku!
        CompositeFileListFilter<File> filter = new CompositeFileListFilter<>();
        filter.addFilter(new SimplePatternFileListFilter("*.csv"));
        filter.addFilter(new AcceptOnceFileListFilter<>());
        source.setFilter(filter);

        return source;
    }

    @ServiceActivator(inputChannel = "fileInputChannel")
    public void processFile(File file) {
        System.out.println("Spring Integration Yeni Dosya Yakaladı ve Yönlendiriyor: " + file.getName());

        String fileName = file.getName().toLowerCase();

        // Spring Integration Yönlendirme (Routing) Mantığı
        if (fileName.contains("ogrenci") || fileName.contains("student")) {

            System.out.println("-> Yönlendirme: Multi-Thread Öğrenci Kayıt İşlemi Tetikleniyor...");

            // 3. DİKKAT: Eski Batch Job yerine yeni Multi-Thread servisimizi çağırıyoruz
            studentMultiThreadService.processFileWithThreads(file);

            // İşlem başarıyla bittiğinde dosya adını .done olarak değiştirebiliriz
            File doneFile = new File(file.getAbsolutePath() + ".done");
            file.renameTo(doneFile);

        } else if (fileName.contains("course") || fileName.contains("ders") || fileName.contains("courses")) {

            System.out.println("-> Yönlendirme: Ders (Course) Batch Job'u tetikleniyor...");
            csvJobService.runCourseJob(file);

        } else {
            System.out.println("-> Uyarı: Bu dosya ismiyle eşleşen bir yönlendirme kuralı bulunamadı.");
        }
    }
}