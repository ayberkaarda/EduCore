package com.example.project3.endpoint;

import com.example.project3.entity.Account;
import com.example.project3.entity.Role;
import com.example.project3.repository.AccountRepository;
import com.example.project3.soap.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ws.server.endpoint.annotation.Endpoint;
import org.springframework.ws.server.endpoint.annotation.PayloadRoot;
import org.springframework.ws.server.endpoint.annotation.RequestPayload;
import org.springframework.ws.server.endpoint.annotation.ResponsePayload;

import java.util.List;

@Endpoint
public class StudentSoapEndpoint {

    private static final String NAMESPACE_URI = "http://example.com/project3/soap";

    @Autowired
    private AccountRepository accountRepository;

    // 1. ÖĞRENCİ EKLEME
    @PayloadRoot(namespace = NAMESPACE_URI, localPart = "RegisterStudentRequest")
    @ResponsePayload
    public RegisterStudentResponse registerStudent(@RequestPayload RegisterStudentRequest request) {
        RegisterStudentResponse response = new RegisterStudentResponse();

        try {
            Account student = new Account();
            // XSD'den gelen alanları entity'mize (Account) mapliyoruz
            student.setFullName(request.getFirstName() + " " + request.getLastName());
            student.setRole(Role.USER);

            accountRepository.save(student);

            response.setStatus("SUCCESS");
            response.setMessage("Öğrenci (" + request.getFirstName() + " " + request.getLastName() + ") başarıyla eklendi.");

        } catch (Exception e) {
            response.setStatus("FAILED");
            response.setMessage("Öğrenci eklenirken hata oluştu: " + e.getMessage());
        }

        return response;
    }

    // 2. ÖĞRENCİLERİ LİSTELEME
    @PayloadRoot(namespace = NAMESPACE_URI, localPart = "GetAllStudentsRequest")
    @ResponsePayload
    public GetAllStudentsResponse getAllStudents(@RequestPayload GetAllStudentsRequest request) {
        GetAllStudentsResponse response = new GetAllStudentsResponse();

        // Veritabanındaki tüm hesapları çekiyoruz
        List<Account> accounts = accountRepository.findAll();

        // Veritabanı Entity'lerini SOAP XML Response objelerine dönüştürüyoruz
        for (Account account : accounts) {
            StudentInfo studentInfo = new StudentInfo();
            studentInfo.setId(account.getId());
            studentInfo.setFullName(account.getFullName() != null ? account.getFullName() : "İsimsiz");

            response.getStudents().add(studentInfo);
        }

        return response;
    }
}