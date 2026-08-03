package com.example.project3.endpoint;

import com.example.project3.entity.Account;
import com.example.project3.entity.Role;
import com.example.project3.repository.AccountRepository;
import com.example.project3.soap.RegisterStudentRequest;
import com.example.project3.soap.RegisterStudentResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ws.server.endpoint.annotation.Endpoint;
import org.springframework.ws.server.endpoint.annotation.PayloadRoot;
import org.springframework.ws.server.endpoint.annotation.RequestPayload;
import org.springframework.ws.server.endpoint.annotation.ResponsePayload;

@Endpoint
public class StudentSoapEndpoint {

    private static final String NAMESPACE_URI = "http://example.com/project3/soap";

    @Autowired
    private AccountRepository accountRepository;

    @PayloadRoot(namespace = NAMESPACE_URI, localPart = "RegisterStudentRequest")
    @ResponsePayload
    public RegisterStudentResponse registerStudent(@RequestPayload RegisterStudentRequest request) {
        RegisterStudentResponse response = new RegisterStudentResponse();

        try {
            Account student = new Account();
            // Not: Entity'ndeki field isimlerine göre metod isimlerini ayarlayabilirsin.
            student.setFullName(request.getFirstName() + " " + request.getLastName());
            student.setRole(Role.USER);

            accountRepository.save(student);

            response.setStatus("SUCCESS");
            response.setMessage("Öğrenci başarıyla eklendi.");

        } catch (Exception e) {
            response.setStatus("FAILED");
            response.setMessage("Öğrenci eklenirken hata oluştu: " + e.getMessage());
        }

        return response;
    }
}