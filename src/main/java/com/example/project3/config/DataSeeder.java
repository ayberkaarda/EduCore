package com.example.project3.config;

import com.example.project3.entity.Account;
import com.example.project3.entity.Course;
import com.example.project3.entity.Role;
import com.example.project3.repository.AccountRepository;
import com.example.project3.repository.CourseRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class DataSeeder {

    @Bean
    CommandLineRunner initDatabase(AccountRepository accountRepo, CourseRepository courseRepo) {
        return args -> {
            if (courseRepo.count() == 0) {
                courseRepo.saveAll(List.of(
                        Course.builder().name("Advanced Web Development").term("2026/1").build(),
                        Course.builder().name("Data Structures and Algorithms").term("2026/1").build(),
                        Course.builder().name("Systems Programming (Rust)").term("2026/2").build(),
                        Course.builder().name("Fundamentals of AI").term("2026/2").build()
                ));
            }

            if (accountRepo.count() == 0) {
                accountRepo.saveAll(List.of(
                        Account.builder().firstName("Ayberk").lastName("Arda").studentNumber("2401001").role(Role.STUDENT).build(),
                        Account.builder().firstName("Ali").lastName("Yilmaz").studentNumber("2401002").role(Role.STUDENT).build(),
                        Account.builder().firstName("Zeynep").lastName("Kaya").studentNumber("2401003").role(Role.STUDENT).build(),
                        Account.builder().firstName("Ahmet").lastName("Smith").studentNumber(null).role(Role.ACADEMICIAN).build()
                ));
            }
        };
    }
}