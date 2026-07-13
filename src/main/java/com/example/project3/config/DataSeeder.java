package com.example.project3.config;

import com.example.project3.entity.Account;
import com.example.project3.entity.Course;
import com.example.project3.entity.Role;
import com.example.project3.repository.AccountRepository;
import com.example.project3.repository.CourseRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.util.List;

@Configuration
public class DataSeeder {

    @Bean
    CommandLineRunner initDatabase(AccountRepository accountRepo, CourseRepository courseRepo, PasswordEncoder passwordEncoder) {
        return args -> {
            if (courseRepo.count() == 0) {
                // DÜZELTME: Başlangıç derslerine instructor (hoca) isimleri eklendi!
                courseRepo.saveAll(List.of(
                        Course.builder().name("Advanced Web Development").term("2026/1").instructor("Engin Demirog").build(),
                        Course.builder().name("Data Structures and Algorithms").term("2026/1").instructor("Fettah Hoca").build(),
                        Course.builder().name("Systems Programming (Rust)").term("2026/2").instructor("Muharrem Hoca").build(),
                        Course.builder().name("Fundamentals of AI").term("2026/2").instructor("Nazım Şentürk").build()
                ));
            }

            if (accountRepo.count() == 0) {
                accountRepo.saveAll(List.of(
                        Account.builder().username("admin").password(passwordEncoder.encode("1234")).firstName("Admin").lastName("Bey").studentNumber("1111").role(Role.ADMIN).build(),
                        Account.builder().username("ayberk").password(passwordEncoder.encode("1234")).firstName("Ayberk").lastName("Arda").studentNumber("2400006446").role(Role.USER).build(),
                        Account.builder().username("ali").password(passwordEncoder.encode("1234")).firstName("Ali").lastName("Yilmaz").studentNumber("2401002").role(Role.USER).build()
                ));
            }
        };
    }
}