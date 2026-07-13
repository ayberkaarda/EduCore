package com.example.project3.service;

import com.example.project3.entity.Account;
import com.example.project3.entity.JobLog;
import com.example.project3.entity.Role;
import com.example.project3.repository.AccountRepository;
import com.example.project3.repository.JobLogRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.time.LocalDateTime;

@Service
public class CsvJobService {

    @Autowired private AccountRepository accountRepository;
    @Autowired private JobLogRepository jobLogRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    // CSV'lerin atılacağı klasör
    private final String DIRECTORY_PATH = "csv_uploads";

    @Scheduled(fixedDelay = 30000) // 30 saniyede bir tetiklenir (Spring @Scheduled Job)
    public void processCsvFiles() {
        File folder = new File(DIRECTORY_PATH);
        if (!folder.exists()) {
            folder.mkdirs(); // Klasör yoksa oluşturur
            return;
        }

        File[] files = folder.listFiles((dir, name) -> name.toLowerCase().endsWith(".csv"));
        if (files == null || files.length == 0) return;

        for (File file : files) {
            int successCount = 0;
            int errorCount = 0;
            boolean isFileFailed = false;

            try (BufferedReader br = new BufferedReader(new FileReader(file))) {
                String line;
                boolean isFirstLine = true;

                while ((line = br.readLine()) != null) {
                    // İlk satır başlık (FirstName,LastName,StudentNumber) kabul ediyoruz
                    if (isFirstLine) { isFirstLine = false; continue; }

                    String[] data = line.split(",");
                    if (data.length < 3) {
                        errorCount++;
                        continue;
                    }

                    String firstName = data[0].trim();
                    String lastName = data[1].trim();
                    String studentNumber = data[2].trim();

                    // Öğrenci numarası benzersizlik kontrolü
                    if (accountRepository.findByStudentNumber(studentNumber).isPresent()) {
                        errorCount++;
                        continue;
                    }

                    try {
                        String generatedUsername = firstName.toLowerCase() + System.currentTimeMillis() % 1000;
                        Account account = Account.builder()
                                .firstName(firstName)
                                .lastName(lastName)
                                .studentNumber(studentNumber)
                                .username(generatedUsername)
                                .password(passwordEncoder.encode("1234")) // Varsayılan şifre
                                .role(Role.USER)
                                .build();
                        accountRepository.save(account);
                        successCount++;
                    } catch (Exception e) {
                        errorCount++;
                    }
                }
            } catch (Exception e) {
                isFileFailed = true; // Dosya hiç okunamadıysa
            }

            // Log Tablosuna Kayıt
            JobLog log = JobLog.builder()
                    .fileName(file.getName())
                    .successfulRecords(successCount)
                    .failedRecords(errorCount)
                    .status(isFileFailed ? "FAILED" : "SUCCESS")
                    .createdAt(LocalDateTime.now())
                    .build();
            jobLogRepository.save(log);

            // İşlem bitince dosya uzantısını değiştir
            String newExtension = isFileFailed ? ".fail" : ".done";
            File newFile = new File(file.getAbsolutePath() + newExtension);
            file.renameTo(newFile);

            System.out.println("Job Bitti: " + file.getName() + " -> " + newExtension);
        }
    }
}