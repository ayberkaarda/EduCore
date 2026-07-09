package com.example.project3.controller;

import com.example.project3.dto.CourseDTO;
import com.example.project3.dto.EnrollmentRequest;
import com.example.project3.dto.AccountDTO;
import com.example.project3.entity.*;
import com.example.project3.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
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

    @PostMapping("/enroll")
    public ResponseEntity<?> enrollCourse(@RequestBody EnrollmentRequest request) {

        Account account = accountRepository.findById(request.accountId()).orElseThrow();
        Course course = courseRepository.findById(request.courseId()).orElseThrow();

        if(enrollmentRepository.existsByAccountIdAndCourseId(account.getId(), course.getId())) {
            return ResponseEntity.badRequest().body("{\"error\": \"User is already enrolled in this course.\"}");
        }

        Enrollment enrollment = Enrollment.builder()
                .account(account)
                .course(course)
                .build();

        enrollmentRepository.save(enrollment);
        return ResponseEntity.ok("Enrolled successfully.");
    }

    @GetMapping("/accounts/students")
    public org.springframework.data.domain.Page<AccountDTO> searchStudents(
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(page, size);
        return accountRepository.searchAccountsByRole(Role.STUDENT, search, pageable)
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
        return ResponseEntity.ok("Role successfully updated.");
    }

    @PostMapping("/accounts/student")
    public ResponseEntity<?> createStudent(@RequestBody Account account) {
        account.setRole(Role.STUDENT);

        long totalUsers = accountRepository.count();
        account.setStudentNumber("260" + (1000 + totalUsers));

        Account savedAccount = accountRepository.save(account);
        return ResponseEntity.ok(savedAccount);
    }

    @PostMapping("/courses")
    public ResponseEntity<?> createCourse(@RequestBody Course course) {
        Course savedCourse = courseRepository.save(course);
        return ResponseEntity.ok(new CourseDTO(savedCourse.getId(), savedCourse.getName(), savedCourse.getTerm()));
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
        accountRepository.save(account);
        return ResponseEntity.ok("{\"message\": \"Updated successfully.\"}");
    }
}