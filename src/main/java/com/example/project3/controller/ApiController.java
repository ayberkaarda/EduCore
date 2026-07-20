package com.example.project3.controller;

import com.example.project3.dto.CourseDTO;
import com.example.project3.dto.EnrollmentRequest;
import com.example.project3.dto.AccountDTO;
import com.example.project3.entity.*;
import com.example.project3.repository.*;
import com.example.project3.util.IpAddressUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@CrossOrigin(origins = "http://localhost:5173")
@RestController
@RequestMapping("/api/v1")
public class ApiController {

    @Autowired private AccountRepository accountRepository;
    @Autowired private CourseRepository courseRepository;
    @Autowired private EnrollmentRepository enrollmentRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private IpBlockRepository ipBlockRepository;
    @Autowired private JobLogRepository jobLogRepository;
    

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
                // BURAYA a.getIpAddress() EKLENDİ
                .map(a -> new AccountDTO(a.getId(), a.getFirstName(), a.getLastName(), a.getStudentNumber(), a.getRole(), a.getIpAddress()));
    }

    @GetMapping("/accounts")
    public org.springframework.data.domain.Page<AccountDTO> searchAllAccounts(
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(page, size);
        return accountRepository.searchAllAccounts(search, pageable)
                // BURAYA a.getIpAddress() EKLENDİ
                .map(a -> new AccountDTO(a.getId(), a.getFirstName(), a.getLastName(), a.getStudentNumber(), a.getRole(), a.getIpAddress()));
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
        // 1. Öğrenciyi veritabanından çek
        System.out.println("react ip adress: " + updatedData.getIpAddress());
        Account account = accountRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Student could not be found."));

        // 2. Temel bilgileri güncelle
        account.setFirstName(updatedData.getFirstName());
        account.setLastName(updatedData.getLastName());
        account.setStudentNumber(updatedData.getStudentNumber());

        // 3. IP Adresi güncelleme ve KONTROL mantığı
        String newIp = updatedData.getIpAddress();

        if (newIp != null && !newIp.trim().isEmpty()) {

            // KURAL 1: Kusursuz IPv4 formatında mı?
            if (!IpAddressUtil.isValidIpv4(newIp)) {
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid format"));
            }

            // KURAL 2: Girilen IP, izin verilen IpBlock'lardan (RANGE, CIDR, STATIC) birinin içinde mi?
            long ipAsLong = IpAddressUtil.ipToLong(newIp);
            boolean isIpAllowed = ipBlockRepository.findAll().stream()
                    .anyMatch(block -> ipAsLong >= block.getStartIp() && ipAsLong <= block.getEndIp());

            if (!isIpAllowed) {
                return ResponseEntity.badRequest().body(Map.of("error", "This IP address is not located in defined IP December or subnets (Subnet)!"));
            }

            account.setIpAddress(newIp);
        } else {
            account.setIpAddress(null); // İptal edilirse boşalt
        }

        // 4. Veritabanına kaydet ve KURAL 3: Unique Constraint Hatasını Yakala
        try {
            accountRepository.save(account);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // Eğer veritabanı Unique Constraint patlatırsa buraya düşer
            return ResponseEntity.badRequest().body(Map.of("error", "This IP address was assigned to another student! Each student should get a different IP."));
        }

        return ResponseEntity.ok(Map.of("message", "Student successfully updated."));
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
        course.setInstructor(updatedData.getInstructor()); // DÜZELTME: Güncelleme esnasında Hoca da güncellenir!
        courseRepository.save(course);
        return ResponseEntity.ok("{\"message\": \"Course updated successfully.\"}");
    }

    @GetMapping("/logs")
    public ResponseEntity<?> getJobLogs() {
        return ResponseEntity.ok(jobLogRepository.findAll(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt")));
    }
    // GetAllCourses metodunu güncelle:
    @GetMapping("/courses")
    public List<CourseDTO> getAllCourses() {
        return courseRepository.findAll().stream()
                .map(c -> new CourseDTO(c.getId(), c.getName(), c.getTerm())) // YENİ
                .collect(Collectors.toList());
    }
    @GetMapping("/ips")
    public ResponseEntity<?> getAllIps() {
        // Sistemdeki tüm IP bloklarını / tanımlarını frontend'e gönder
        return ResponseEntity.ok(ipBlockRepository.findAll());
    }

    // GetEnrolledCourses metodunu güncelle:
    @GetMapping("/accounts/{accountId}/courses")
    public List<CourseDTO> getEnrolledCourses(@PathVariable Long accountId) {
        return enrollmentRepository.findByAccountId(accountId).stream()
                .map(e -> new CourseDTO(e.getCourse().getId(), e.getCourse().getName(), e.getCourse().getTerm())) // YENİ
                .collect(Collectors.toList());
    }

    // --- YENİ EKLENDİ: Toplu Log Silme İşlemi ---
    // --- GÜNCELLENDİ: Toplu Log Silme İşlemi (Kurşun Geçirmez Versiyon) ---
    @DeleteMapping("/logs")
    public ResponseEntity<?> deleteLogs(@RequestParam String ids) {
        try {
            // "1,2,3" şeklinde gelen metni parçalayıp Long listesine çeviriyoruz
            List<Long> idList = java.util.Arrays.stream(ids.split(","))
                    .map(Long::parseLong)
                    .collect(Collectors.toList());
            jobLogRepository.deleteAllById(idList);
            return ResponseEntity.ok("{\"message\": \"Selected logs deleted successfully.\"}");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("{\"error\": \"Failed to delete logs.\"}");
        }
    }
    @GetMapping("/ip-blocks")
    public List<IpBlock> getAllIpBlocks() {
        return ipBlockRepository.findAll();
    }

    @DeleteMapping("/ip-blocks/{id}")
    public ResponseEntity<?> deleteIpBlock(@PathVariable Long id) {
        ipBlockRepository.deleteById(id);
        return ResponseEntity.ok("{\"message\": \"IP Block deleted.\"}");
    }

    @PostMapping("/ip-blocks")
    public ResponseEntity<?> createIpBlock(@RequestBody IpBlock block) {
        try {
            if ("STATIC".equals(block.getType())) {
                if (!com.example.project3.util.IpAddressUtil.isValidIpv4(block.getOriginalValue()))
                    throw new Exception("Invalid IPv4 address format.");
                long val = com.example.project3.util.IpAddressUtil.ipToLong(block.getOriginalValue());
                block.setStartIp(val);
                block.setEndIp(val);
            } else if ("RANGE".equals(block.getType())) {
                String[] parts = block.getOriginalValue().split("-");
                if (parts.length != 2) throw new Exception("Invalid Range format (e.g. 192.168.1.1-192.168.1.10).");
                String start = parts[0].trim();
                String end = parts[1].trim();
                if (!com.example.project3.util.IpAddressUtil.isValidIpv4(start) || !com.example.project3.util.IpAddressUtil.isValidIpv4(end))
                    throw new Exception("Invalid IPs inside range.");
                long startL = com.example.project3.util.IpAddressUtil.ipToLong(start);
                long endL = com.example.project3.util.IpAddressUtil.ipToLong(end);
                if (endL < startL) throw new Exception("End IP cannot be smaller than Start IP.");
                block.setStartIp(startL);
                block.setEndIp(endL);
            } else if ("CIDR".equals(block.getType())) {
                String[] parts = block.getOriginalValue().split("/");
                if (parts.length != 2) throw new Exception("Invalid CIDR format (e.g. 192.168.1.0/24).");
                String ip = parts[0].trim();
                int prefix = Integer.parseInt(parts[1].trim());
                if (!com.example.project3.util.IpAddressUtil.isValidIpv4(ip) || prefix < 0 || prefix > 32)
                    throw new Exception("Invalid CIDR values.");
                long ipL = com.example.project3.util.IpAddressUtil.ipToLong(ip);
                long mask = prefix == 0 ? 0 : (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
                long startL = ipL & mask;
                long endL = startL | (~mask & 0xFFFFFFFFL);
                block.setStartIp(startL);
                block.setEndIp(endL);
            } else {
                throw new Exception("Unknown Type.");
            }
            return ResponseEntity.ok(ipBlockRepository.save(block));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("{\"error\": \"" + e.getMessage() + "\"}");
        }
    }
}