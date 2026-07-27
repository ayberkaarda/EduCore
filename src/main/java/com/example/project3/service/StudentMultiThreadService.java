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
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class StudentMultiThreadService {

    private final AccountRepository accountRepository;
    private final PasswordEncoder passwordEncoder;
    private final JobLogRepository jobLogRepository;
    private final Random random = new Random(); // Simülasyon için rastgele süre üreteci

    public StudentMultiThreadService(AccountRepository accountRepository, PasswordEncoder passwordEncoder, JobLogRepository jobLogRepository) {
        this.accountRepository = accountRepository;
        this.passwordEncoder = passwordEncoder;
        this.jobLogRepository = jobLogRepository;
    }

    public boolean processFileWithThreads(File file) {
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        List<String> detailedLogs = new CopyOnWriteArrayList<>();

        ExecutorService executor = Executors.newFixedThreadPool(5);
        System.out.println("\n=======================================================");
        System.out.println("🚀 MULTI-THREAD ÖĞRENCİ KAYIT İŞLEMİ BAŞLADI!");
        System.out.println("=======================================================\n");

        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            boolean isFirstLine = true;
            List<String> chunk = new ArrayList<>();

            while ((line = br.readLine()) != null) {
                if (isFirstLine) { isFirstLine = false; continue; }
                if (line.trim().isEmpty()) continue;

                chunk.add(line);

                if (chunk.size() == 5) {
                    processChunk(chunk, executor, successCount, failCount, detailedLogs);
                    chunk = new ArrayList<>();
                }
            }

            if (!chunk.isEmpty()) {
                processChunk(chunk, executor, successCount, failCount, detailedLogs);
            }

        } catch (Exception e) {
            System.err.println("Dosya okuma hatası: " + e.getMessage());
            return false;
        }

        executor.shutdown();
        try {
            executor.awaitTermination(1, TimeUnit.HOURS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        JobLog jobLog = new JobLog();
        jobLog.setFileName(file.getName());
        jobLog.setEntityType("STUDENTS");
        jobLog.setSuccessfulRecords(successCount.get());
        jobLog.setFailedRecords(failCount.get());
        jobLog.setCreatedAt(LocalDateTime.now());
        jobLog.setStatus(failCount.get() > 0 ? "FAILED" : "SUCCESS");
        jobLog.setDetailedLogs(String.join("\n", detailedLogs));

        jobLogRepository.save(jobLog);

        System.out.println("\n=======================================================");
        System.out.println("🏁 TÜM İŞLEMLER BİTTİ. LOG VERİTABANINA YAZILDI.");
        System.out.println("=======================================================\n");

        return true;
    }

    private void processChunk(List<String> chunk, ExecutorService executor, AtomicInteger successCount, AtomicInteger failCount, List<String> detailedLogs) {
        
        CyclicBarrier barrier = new CyclicBarrier(chunk.size(), () -> {
            System.out.println("\n   ---> 🟢 BARİYER AÇILDI! [" + chunk.size() + " Thread] arkadaşını bekledi ve işini başarıyla bitirdi.\n");
        });

        for (String line : chunk) {
            executor.submit(() -> {
                String threadName = Thread.currentThread().getName();
                String[] data = line.split(",");
                String studentName = data.length > 1 ? data[0] + " " + data[1] : "Bilinmeyen";

                try {
                    System.out.println(" 🏃‍♂️ [" + threadName + "] " + studentName + " isimli öğrenciyi okumaya başladı...");
                    
                    // SİMÜLASYON: Her thread rastgele 1 ile 3 saniye arası bir sürede işlem yapıyor gibi uyur.
                    int sleepTime = 1000 + random.nextInt(2000); 
                    Thread.sleep(sleepTime);

                    saveStudentToDatabase(line, successCount, failCount, detailedLogs, threadName, studentName);
                    
                    System.out.println(" 🛑 [" + threadName + "] " + studentName + " işlemini bitirdi. Bariyerde diğerlerini bekliyor...");
                    
                    barrier.await(); 

                } catch (Exception e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
    }

    private void saveStudentToDatabase(String csvLine, AtomicInteger successCount, AtomicInteger failCount, List<String> detailedLogs, String threadName, String studentName) {
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
                successCount.incrementAndGet();
                detailedLogs.add("✅ " + studentName + " - Successfully added.");
            } catch (DataIntegrityViolationException e) {
                failCount.incrementAndGet();
                detailedLogs.add("❌ " + studentName + " - Failed: Student number (" + studentNum + ") already exists.");
            } catch (Exception e) {
                failCount.incrementAndGet();
                detailedLogs.add("❌ " + studentName + " - Failed: " + e.getMessage());
            }
        }
    }
}