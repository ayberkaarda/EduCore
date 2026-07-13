package com.example.project3.service;

import com.example.project3.config.JobTracker;
import com.example.project3.entity.JobLog;
import com.example.project3.repository.JobLogRepository;
import org.springframework.batch.core.*;
import org.springframework.batch.core.launch.JobLauncher;
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
    @Autowired private JobTracker jobTracker; // YENİ EKLENDİ
    @Autowired private Job importCourseJob;

    private final String DIRECTORY_PATH = "csv_uploads";

    @Scheduled(fixedDelay = 30000)
    public void runBatchJob() {
        File folder = new File(DIRECTORY_PATH);
        if (!folder.exists()) { folder.mkdirs(); return; }

        File[] files = folder.listFiles((dir, name) -> name.toLowerCase().endsWith(".csv"));
        if (files == null || files.length == 0) return;

        for (File file : files) {
            try {
                JobParameters jobParameters = new JobParametersBuilder()
                        .addString("filePath", file.getAbsolutePath())
                        .addLong("time", System.currentTimeMillis())
                        .toJobParameters();

                JobExecution execution = jobLauncher.run(importStudentJob, jobParameters);

                int writeCount = 0;
                int filterAndSkipCount = 0;

                for (StepExecution stepExecution : execution.getStepExecutions()) {
                    writeCount += stepExecution.getWriteCount();
                    filterAndSkipCount += stepExecution.getFilterCount() + stepExecution.getSkipCount();
                }

                boolean isFailed = execution.getStatus() == BatchStatus.FAILED;

                // YENİ: İşlem bitince JSON logları çek ve hafızayı temizle
                String detailedLogsJson = jobTracker.getLogsAsJson(execution.getJobId());
                jobTracker.clear(execution.getJobId());

                JobLog log = JobLog.builder()
                        .fileName(file.getName())
                        .successfulRecords(writeCount)
                        .failedRecords(filterAndSkipCount)
                        .status(isFailed ? "FAILED" : "SUCCESS")
                        .detailedLogs(detailedLogsJson) // YENİ: Veritabanına kaydet
                        .createdAt(LocalDateTime.now())
                        .build();
                jobLogRepository.save(log);

                String newExtension = isFailed ? ".fail" : ".done";
                file.renameTo(new File(file.getAbsolutePath() + newExtension));

                System.out.println("Spring Batch Bitti: " + file.getName() + " -> " + newExtension);

            } catch (Exception e) {
                e.printStackTrace();
                file.renameTo(new File(file.getAbsolutePath() + ".fail"));
            }
        }
    }
}