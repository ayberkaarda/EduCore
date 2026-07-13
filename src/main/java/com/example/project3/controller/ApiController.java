package com.example.project3.controller;

import com.example.project3.dto.CourseDTO;
import com.example.project3.dto.EnrollmentRequest;
import com.example.project3.dto.AccountDTO;
import com.example.project3.entity.*;
import com.example.project3.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@CrossOrigin(origins = "http://localhost:5173")
@RestController
@RequestMapping("/api/v1")
public class ApiController {

    @Autowired private AccountRepository accountRepository;
    @Autowired private CourseRepository courseRepository;
    @Autowired private EnrollmentRepository enrollmentRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JobLogRepository jobLogRepository;

    @GetMapping("/courses")
    public List<CourseDTO> getAllCourses() {
        return courseRepository.findAll().stream()
                .map(c -> new CourseDTO(c.getId(), c.getName(), c.getTerm()))
                .collect(Collectors.toList());
    }

    @GetMapping("/accounts/{accountId}/courses")
    public List<CourseDTO> getEnrolledCourses(@PathVariable Long accountId) {
        return enrollmentRepository.findByAccountId(accountId).stream()
                .map(e -> new CourseDTO(e.getCourse().getId(), e.getCourse().getName(), e.getCourse().getTerm()))
                .collect(Collectors.toList());
    }

    // --- 1. DERS EKLEME (GÜVENLİK KALKANLI) ---
    @PostMapping("/enroll")
    public ResponseEntity<?> enrollCourse(@RequestBody EnrollmentRequest request) {
        // Giren kişiyi yakala
        Account loggedInUser = (Account) org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication().getPrincipal();

        // KURAL: Admin değilse VE girdiği ID kendi ID'si değilse YASAKLA
        if (loggedInUser.getRole() != Role.ADMIN && !loggedInUser.getId().equals(request.accountId())) {
            return ResponseEntity.status(403).body("{\"error\": \"You can only modify your own courses!\"}");
        }

        Account account = accountRepository.findById(request.accountId()).orElseThrow();
        Course course = courseRepository.findById(request.courseId()).orElseThrow();

        if(enrollmentRepository.existsByAccountIdAndCourseId(account.getId(), course.getId())) {
            return ResponseEntity.badRequest().body("{\"error\": \"User is already enrolled in this course.\"}");
        }

        Enrollment enrollment = Enrollment.builder().account(account).course(course).build();
        enrollmentRepository.save(enrollment);
        return ResponseEntity.ok("{\"message\": \"Enrolled successfully.\"}");
    }

    // --- 2. YENİ EKLENDİ: DERSİ BIRAKMA/SİLME (GÜVENLİK KALKANLI) ---
    @DeleteMapping("/accounts/{accountId}/courses/{courseId}")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<?> dropCourse(@PathVariable Long accountId, @PathVariable Long courseId) {
        // Giren kişiyi yakala
        Account loggedInUser = (Account) org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication().getPrincipal();

        // KURAL: Admin değilse VE girdiği ID kendi ID'si değilse YASAKLA
        if (loggedInUser.getRole() != Role.ADMIN && !loggedInUser.getId().equals(accountId)) {
            return ResponseEntity.status(403).body("{\"error\": \"You can only drop your own courses!\"}");
        }

        enrollmentRepository.deleteByAccountIdAndCourseId(accountId, courseId);
        return ResponseEntity.ok("{\"message\": \"Course dropped successfully.\"}");
    }

    @GetMapping("/accounts/students")
    public org.springframework.data.domain.Page<AccountDTO> searchStudents(
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(page, size);
        return accountRepository.searchAccountsByRole(Role.USER, search, pageable)
                .map(a -> new AccountDTO(a.getId(), a.getFirstName(), a.getLastName(), a.getStudentNumber(), a.getRole()));
    }

    @GetMapping("/accounts")
    public org.springframework.data.domain.Page<AccountDTO> searchAllAccounts(
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(page, size);
        return accountRepository.searchAllAccounts(search, pageable)
                .map(a -> new AccountDTO(a.getId(), a.getFirstName(), a.getLastName(), a.getStudentNumber(), a.getRole()));
    }

    @PutMapping("/accounts/{id}/role")
    public ResponseEntity<?> updateRole(@PathVariable Long id, @RequestBody java.util.Map<String, String> payload) {
        Account account = accountRepository.findById(id).orElseThrow();
        account.setRole(Role.valueOf(payload.get("role")));
        accountRepository.save(account);
        return ResponseEntity.ok("{\"message\": \"Role successfully updated.\"}");
    }

    @PostMapping("/accounts/student")
    public ResponseEntity<?> createStudent(@RequestBody Account account) {
        account.setRole(Role.USER);

        if (account.getUsername() == null) {
            String generatedUsername = account.getFirstName().toLowerCase().replaceAll("\\s+", "") + (System.currentTimeMillis() % 1000);
            account.setUsername(generatedUsername);
        }
        if (account.getPassword() == null) {
            account.setPassword(passwordEncoder.encode("1234"));
        }

        Account savedAccount = accountRepository.save(account);
        return ResponseEntity.ok(savedAccount);
    }

    @DeleteMapping("/accounts/{id}")
    public ResponseEntity<?> deleteAccount(@PathVariable Long id) {
        accountRepository.deleteById(id);
        return ResponseEntity.ok("{\"message\": \"Deleted successfully.\"}");
    }

    @PutMapping("/accounts/{id}")
    public ResponseEntity<?> updateAccount(@PathVariable Long id, @RequestBody Account updatedData) {
        Account account = accountRepository.findById(id).orElseThrow();
        account.setFirstName(updatedData.getFirstName());
        account.setLastName(updatedData.getLastName());
        account.setStudentNumber(updatedData.getStudentNumber());
        accountRepository.save(account);
        return ResponseEntity.ok("{\"message\": \"Updated successfully.\"}");
    }

    @PostMapping("/courses")
    public ResponseEntity<?> createCourse(@RequestBody Course course) {
        Course savedCourse = courseRepository.save(course);
        return ResponseEntity.ok(new CourseDTO(savedCourse.getId(), savedCourse.getName(), savedCourse.getTerm()));
    }

    @DeleteMapping("/courses/{id}")
    public ResponseEntity<?> deleteCourse(@PathVariable Long id) {
        courseRepository.deleteById(id);
        return ResponseEntity.ok("{\"message\": \"Course deleted successfully.\"}");
    }

    @PutMapping("/courses/{id}")
    public ResponseEntity<?> updateCourse(@PathVariable Long id, @RequestBody Course updatedData) {
        Course course = courseRepository.findById(id).orElseThrow();
        course.setName(updatedData.getName());
        course.setTerm(updatedData.getTerm());
        courseRepository.save(course);
        return ResponseEntity.ok("{\"message\": \"Course updated successfully.\"}");
    }

    @GetMapping("/logs")
    public ResponseEntity<?> getJobLogs() {
        return ResponseEntity.ok(jobLogRepository.findAll(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt")));
    }
}