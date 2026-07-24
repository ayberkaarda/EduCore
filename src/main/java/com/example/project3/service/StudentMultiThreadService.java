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

    public StudentMultiThreadService(AccountRepository accountRepository, PasswordEncoder passwordEncoder) {
        this.accountRepository = accountRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public void processFileWithThreads(File file) {
        List<String> lines = new ArrayList<>();

        // 1. Dosyayı Oku ve Satırları Listeye Al
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            boolean isFirstLine = true;
            while ((line = br.readLine()) != null) {
                if (isFirstLine) {
                    isFirstLine = false;
                    continue; // Başlık satırını atla
                }
                lines.add(line);
            }
        } catch (Exception e) {
            System.err.println("Dosya okuma hatası: " + e.getMessage());
            return;
        }

        // 2. Maksimum 5 eşzamanlı işlem yapacak Thread Havuzu
        ExecutorService executor = Executors.newFixedThreadPool(5);

        // 3. Verileri 5'erli gruplar (Chunk) halinde işle
        for (int i = 0; i < lines.size(); i += 5) {
            int end = Math.min(i + 5, lines.size());
            List<String> chunk = lines.subList(i, end);

            // Bu gruba özel bariyer (Grupta kaç kişi varsa o kadar bekleyecek)
            CyclicBarrier barrier = new CyclicBarrier(chunk.size(), () -> {
                System.out.println("✅ [" + chunk.size() + " Kişilik Grup] Thread'ler birbiriyle iletişimi tamamladı ve grup veritabanına işlendi!\n");
            });

            // Gruptaki her bir öğrenci için thread'leri ateşle
            for (String line : chunk) {
                executor.submit(() -> {
                    try {
                        saveStudentToDatabase(line);

                        // İşini bitiren thread, gruptaki diğer arkadaşlarının işini bitirmesini bekliyor
                        barrier.await();

                    } catch (InterruptedException | BrokenBarrierException e) {
                        Thread.currentThread().interrupt();
                        System.err.println("Thread senkronizasyon hatası: " + e.getMessage());
                    }
                });
            }
        }

        // Tüm görevler havuza gönderildikten sonra havuzu kapat (yeni işlem almaz, mevcutları bitirir)
        executor.shutdown();
    }

    private void saveStudentToDatabase(String csvLine) {
        String threadName = Thread.currentThread().getName();
        String[] data = csvLine.split(",");

        // CSV Formatı varsayımı: first_name, last_name, student_number vs.
        if (data.length >= 3) {
            Account student = new Account();
            student.setFirstName(data[0].trim());
            student.setLastName(data[1].trim());
            student.setStudentNumber(data[2].trim());
            student.setUsername(data[2].trim());
            student.setPassword(passwordEncoder.encode("123456")); // Varsayılan şifre
            student.setRole(Role.USER);

            // Gerçek veritabanı kaydı
            accountRepository.save(student);

            System.out.println("🚀 [" + threadName + "] Öğrenciyi kaydetti ve bariyere ulaştı: " + student.getFirstName());
        }
    }
}