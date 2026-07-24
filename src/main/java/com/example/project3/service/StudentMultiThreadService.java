package com.example.project3.service;

import com.example.project3.entity.Account;
import com.example.project3.entity.JobLog;
import com.example.project3.entity.Role;
import com.example.project3.repository.AccountRepository;
import com.example.project3.repository.JobLogRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class StudentMultiThreadService {

    private final AccountRepository accountRepository;
    private final PasswordEncoder passwordEncoder;
    private final JobLogRepository jobLogRepository; // 1. LOG REPOSITORY EKLENDİ

    public StudentMultiThreadService(AccountRepository accountRepository, PasswordEncoder passwordEncoder, JobLogRepository jobLogRepository) {
        this.accountRepository = accountRepository;
        this.passwordEncoder = passwordEncoder;
        this.jobLogRepository = jobLogRepository;
    }

    public boolean processFileWithThreads(File file) {
        List<String> lines = new ArrayList<>();

        // 2. THREAD-SAFE LOG DEĞİŞKENLERİ
        // Birden fazla thread aynı anda sayı artıracağı için AtomicInteger kullanıyoruz.
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        List<String> detailedLogs = new CopyOnWriteArrayList<>(); // Thread-safe liste

        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            boolean isFirstLine = true;
            while ((line = br.readLine()) != null) {
                if (isFirstLine) { isFirstLine = false; continue; }
                if (!line.trim().isEmpty()) { lines.add(line); }
            }
        } catch (Exception e) {
            System.err.println("Dosya okuma hatası: " + e.getMessage());
            return false;
        }

        ExecutorService executor = Executors.newFixedThreadPool(5);

        for (int i = 0; i < lines.size(); i += 5) {
            int end = Math.min(i + 5, lines.size());
            List<String> chunk = lines.subList(i, end);

            CyclicBarrier barrier = new CyclicBarrier(chunk.size());

            for (String line : chunk) {
                executor.submit(() -> {
                    try {
                        saveStudentToDatabase(line, successCount, failCount, detailedLogs);
                        barrier.await();
                    } catch (Exception e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
        }

        executor.shutdown();
        try {
            // 3. MAIN THREAD'İ BEKLET: Tüm thread'ler bitene kadar log kaydını yapma
            executor.awaitTermination(1, TimeUnit.HOURS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 4. VERİTABANINA JOB LOG KAYDI OLUŞTURMA
        JobLog jobLog = new JobLog();
        jobLog.setFileName(file.getName());
        jobLog.setEntityType("STUDENTS");
        jobLog.setSuccessfulRecords(successCount.get());
        jobLog.setFailedRecords(failCount.get());
        jobLog.setCreatedAt(LocalDateTime.now());
        jobLog.setStatus(failCount.get() > 0 ? "FAILED" : "SUCCESS");
        jobLog.setDetailedLogs(String.join("\n", detailedLogs)); // Logları alt alta birleştir

        jobLogRepository.save(jobLog);

        return true;
    }

    private void saveStudentToDatabase(String csvLine, AtomicInteger successCount, AtomicInteger failCount, List<String> detailedLogs) {
        String[] data = csvLine.split(",");

        if (data.length >= 3) {
            String firstName = data[0].trim();
            String lastName = data[1].trim();
            String studentNum = data[2].trim();

            Account student = new Account();
            student.setFirstName(firstName);
            student.setLastName(lastName);
            student.setStudentNumber(studentNum);
            student.setUsername(studentNum);
            student.setPassword(passwordEncoder.encode("123456"));
            student.setRole(Role.USER);

            try {
                accountRepository.save(student);
                successCount.incrementAndGet(); // Başarılı sayısını 1 artır
                detailedLogs.add("✅ " + firstName + " " + lastName + " - Successfully added.");
            } catch (DataIntegrityViolationException e) {
                // Veritabanında aynı numara zaten varsa bu hataya (duplicate key) düşer
                failCount.incrementAndGet(); // Başarısız sayısını 1 artır
                detailedLogs.add("❌ " + firstName + " " + lastName + " - Failed: Student number (" + studentNum + ") already exists.");
            } catch (Exception e) {
                failCount.incrementAndGet();
                detailedLogs.add("❌ " + firstName + " " + lastName + " - Failed: " + e.getMessage());
            }
        }
    }
}