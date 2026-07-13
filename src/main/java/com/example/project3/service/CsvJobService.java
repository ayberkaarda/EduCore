package com.example.project3.service;

import com.example.project3.entity.JobLog;
import com.example.project3.repository.JobLogRepository;
import org.springframework.batch.core.*;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.persistence.StepExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.LocalDateTime;

@Service
public class CsvJobService {

    @Autowired private JobLauncher jobLauncher;
    @Autowired private Job importStudentJob;
    @Autowired private JobLogRepository jobLogRepository;

    private final String DIRECTORY_PATH = "csv_uploads";

    @Scheduled(fixedDelay = 30000) // 30 Saniyede bir klasörü dinler
    public void runBatchJob() {
        File folder = new File(DIRECTORY_PATH);
        if (!folder.exists()) { folder.mkdirs(); return; }

        File[] files = folder.listFiles((dir, name) -> name.toLowerCase().endsWith(".csv"));
        if (files == null || files.length == 0) return;

        for (File file : files) {
            try {
                // Job'a Parametre Olarak Dosya Yolunu Veriyoruz
                JobParameters jobParameters = new JobParametersBuilder()
                        .addString("filePath", file.getAbsolutePath())
                        .addLong("time", System.currentTimeMillis()) // Her job benzersiz olmalı
                        .toJobParameters();

                // BATCH JOB'U BAŞLAT
                JobExecution execution = jobLauncher.run(importStudentJob, jobParameters);

                // Batch'in Sonuçlarını Hesapla
                int writeCount = 0; // Başarılı yazılanlar
                int filterAndSkipCount = 0; // Aynı no (Filtrelenen) veya Hatalı olanlar

                for (StepExecution stepExecution : execution.getStepExecutions()) {
                    writeCount += stepExecution.getWriteCount();
                    filterAndSkipCount += stepExecution.getFilterCount() + stepExecution.getSkipCount();
                }

                boolean isFailed = execution.getStatus() == BatchStatus.FAILED;

                // Log Tablosuna Yaz
                JobLog log = JobLog.builder()
                        .fileName(file.getName())
                        .successfulRecords(writeCount)
                        .failedRecords(filterAndSkipCount)
                        .status(isFailed ? "FAILED" : "SUCCESS")
                        .createdAt(LocalDateTime.now())
                        .build();
                jobLogRepository.save(log);

                // Uzantıyı Değiştir
                String newExtension = isFailed ? ".fail" : ".done";
                file.renameTo(new File(file.getAbsolutePath() + newExtension));

                System.out.println("Spring Batch Bitti: " + file.getName() + " -> " + newExtension);

            } catch (Exception e) {
                e.printStackTrace();
                file.renameTo(new File(file.getAbsolutePath() + ".fail")); // Ciddi çökme varsa fail yap
            }
        }
    }
}