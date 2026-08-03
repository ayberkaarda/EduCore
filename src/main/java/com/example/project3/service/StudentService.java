package com.example.project3.service;

import com.example.project3.entity.Student;
import com.example.project3.repository.StudentRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class StudentService {

    private final StudentRepository studentRepository;

    public StudentService(StudentRepository studentRepository) {
        this.studentRepository = studentRepository;
    }

    // Sıralama işlemini backend tarafında gerçekleştirir
    public List<Student> getActiveStudents(String sortBy, String direction) {
        Sort.Direction sortDirection = direction.equalsIgnoreCase("desc") ? Sort.Direction.DESC : Sort.Direction.ASC;
        Sort sort = Sort.by(sortDirection, sortBy); // Örn: "name"

        return studentRepository.findByDeleted(0, sort);
    }

    // Fiziksel silme yerine alanı 1 yapar (Soft Delete)
    public void softDeleteStudent(Long id) {
        Student student = studentRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Öğrenci bulunamadı"));
        student.setDeleted(1);
        studentRepository.save(student);
    }

    // Silinmiş olsa bile aynı kaydın tekrar eklenmesini engeller
    public Student createStudent(Student student) {
        if (studentRepository.existsByStudentNumber(student.getStudentNumber())) {
            throw new RuntimeException("Bu öğrenci numarası ile daha önce bir kayıt oluşturulmuş (silinmiş olsa dahi tekrar eklenemez).");
        }
        student.setDeleted(0);
        return studentRepository.save(student);
    }
}