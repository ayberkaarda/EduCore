package com.example.project3.service;

import com.example.project3.entity.Account;
import com.example.project3.repository.AccountRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class StudentService {

    private final AccountRepository accountRepository;

    public StudentService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    // Sıralama işlemini veritabanı seviyesinde gerçekleştirir
    public List<Account> getActiveStudents(String sortBy, String direction) {
        Sort.Direction sortDirection = direction.equalsIgnoreCase("desc") ? Sort.Direction.DESC : Sort.Direction.ASC;
        Sort sort = Sort.by(sortDirection, sortBy);

        return accountRepository.findByDeleted(0, sort);
    }

    // Soft Delete İşlemi (Account entity üzerinden)
    public void softDeleteStudent(Long id) {
        Account account = accountRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Kayıt bulunamadı"));
        account.setDeleted(1); // Silindi olarak işaretle
        accountRepository.save(account);
    }

    // Silinmiş olsa bile aynı kaydın tekrar eklenmesini engeller
    public Account createStudent(Account account) {
        if (accountRepository.existsByStudentNumber(account.getStudentNumber())) {
            throw new RuntimeException("Bu öğrenci numarası ile daha önce bir kayıt oluşturulmuş.");
        }
        account.setDeleted(0);
        return accountRepository.save(account);
    }
}