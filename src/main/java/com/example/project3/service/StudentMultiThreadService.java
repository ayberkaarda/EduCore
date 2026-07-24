package com.example.project3.service;

import com.example.project3.entity.Account;
import com.example.project3.entity.Role;
import com.example.project3.repository.AccountRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class StudentMultiThreadService {

    private final AccountRepository accountRepository;
    private final PasswordEncoder passwordEncoder;

    // TODO: Arayüzdeki JobLog kayıtlarını oluşturmak için kendi log servisini buraya inject edebilirsin.
    // private final JobTracker jobTracker;

    public StudentMultiThreadService(AccountRepository accountRepository, PasswordEncoder passwordEncoder) {
        this.accountRepository = accountRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public boolean processFileWithThreads(File file) {
        List<String> lines = new ArrayList<>();
        boolean isSuccess = true;

        // 1. Dosyayı Oku
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            boolean isFirstLine = true;
            while ((line = br.readLine()) != null) {
                if (isFirstLine) {
                    isFirstLine = false;
                    continue; // CSV Başlığını atla
                }
                if (!line.trim().isEmpty()) {
                    lines.add(line);
                }
            }
        } catch (Exception e) {
            System.err.println("Dosya okuma hatası: " + e.getMessage());
            return false;
        }

        // 2. Thread Havuzu (Aynı anda 5 işlem)
        ExecutorService executor = Executors.newFixedThreadPool(5);

        // 3. Verileri 5'erli Gruplara Böl
        for (int i = 0; i < lines.size(); i += 5) {
            int end = Math.min(i + 5, lines.size());
            List<String> chunk = lines.subList(i, end);

            // Bu gruptaki kişi sayısı kadar bekleyecek bariyer
            CyclicBarrier barrier = new CyclicBarrier(chunk.size(), () -> {
                System.out.println("✅ [" + chunk.size() + " Kişilik Grup] Thread'ler birbiriyle iletişimi tamamladı!");
            });

            for (String line : chunk) {
                executor.submit(() -> {
                    try {
                        saveStudentToDatabase(line);
                        // İşini bitiren thread, gruptaki diğerlerini bekler
                        barrier.await();
                    } catch (InterruptedException | BrokenBarrierException e) {
                        Thread.currentThread().interrupt();
                        System.err.println("Thread senkronizasyon hatası: " + e.getMessage());
                    }
                });
            }
        }

        executor.shutdown();


        return isSuccess;
    }

    private void saveStudentToDatabase(String csvLine) {
        String threadName = Thread.currentThread().getName();
        String[] data = csvLine.split(",");

        if (data.length >= 3) {
            String studentNum = data[2].trim();
            String firstName = data[0].trim();
            String lastName = data[1].trim();


            Account student = new Account();
            student.setFirstName(firstName);
            student.setLastName(lastName);
            student.setStudentNumber(studentNum);
            student.setUsername(studentNum);
            student.setPassword(passwordEncoder.encode("123456"));
            student.setRole(Role.USER);

            try {
                accountRepository.save(student);
                System.out.println("✅ " + firstName + " " + lastName + " - Successfully added. [" + threadName + "]");
                // jobTracker.logDetail(..., "Successfully added.");
            } catch (Exception e) {
                System.out.println("❌ " + firstName + " - Failed: " + e.getMessage());
                // jobTracker.logDetail(..., "Failed: " + e.getMessage());
            }
        }
    }
}