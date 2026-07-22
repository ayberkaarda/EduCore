package com.example.project3.service;

import com.example.project3.config.JobTracker;
import com.example.project3.entity.JobLog;
import com.example.project3.repository.JobLogRepository;
import org.springframework.batch.core.*;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.LocalDateTime;

@Service
public class CsvJobService {

    @Autowired
    private JobLauncher jobLauncher;
    @Autowired
    private Job importStudentJob;
    @Autowired
    private Job importCourseJob;
    @Autowired
    private JobLogRepository jobLogRepository;
    @Autowired
    private JobTracker jobTracker;

    public void runStudentJob(File file) {
        processBatchJob(file, importStudentJob, "STUDENTS");
    }

    public void runCourseJob(File file) {
        processBatchJob(file, importCourseJob, "COURSES");
    }

    private void processBatchJob(File file, Job jobToRun, String entityType) {
        try {
            JobParameters jobParameters = new JobParametersBuilder()
                    .addString("filePath", file.getAbsolutePath())
                    .addLong("time", System.currentTimeMillis())
                    .toJobParameters();

            JobExecution execution = jobLauncher.run(jobToRun, jobParameters);

            int writeCount = 0;
            int filterAndSkipCount = 0;

            for (StepExecution stepExecution : execution.getStepExecutions()) {
                writeCount += stepExecution.getWriteCount();
                filterAndSkipCount += stepExecution.getFilterCount() + stepExecution.getSkipCount();
            }

            // DEĞİŞİKLİK BURADA: Job çöktüyse VEYA atlanan/hatalı satır varsa FAILED say.
            // Eğer hiçbir satır yazılamadıysa (writeCount == 0) kuralını da ekleyebilirsin.
            boolean isFailed = execution.getStatus() == BatchStatus.FAILED || filterAndSkipCount > 0;

            String detailedLogsJson = jobTracker.getLogsAsJson(execution.getJobId());
            jobTracker.clear(execution.getJobId());

            JobLog log = JobLog.builder()
                    .fileName(file.getName())
                    .successfulRecords(writeCount)
                    .failedRecords(filterAndSkipCount)
                    .status(isFailed ? "FAILED" : "SUCCESS")
                    .entityType(entityType)
                    .detailedLogs(detailedLogsJson)
                    .createdAt(LocalDateTime.now())
                    .build();
            jobLogRepository.save(log);

            String newExtension = isFailed ? ".fail" : ".done";
            file.renameTo(new File(file.getAbsolutePath() + newExtension));

            System.out.println("Spring Integration & Batch Bitti: " + file.getName() + " -> " + newExtension + " (" + entityType + ")");

        } catch (Exception e) {
            e.printStackTrace();
            file.renameTo(new File(file.getAbsolutePath() + ".fail"));
        }
    }
}