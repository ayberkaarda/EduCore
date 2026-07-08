package com.example.project3.exception;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.HashMap;
import java.util.Map;

@ControllerAdvice
public class GlobalExceptionHandler {

    // Unique Constraint (Aynı dersi iki kere alma veya aynı numaradan kayıt olma) hatalarını yakalar
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        Map<String, String> response = new HashMap<>();
        response.put("error", "Bu kayıt zaten mevcut veya kurallarla çelişiyor. (Örn: Öğrenci bu dersi zaten almış olabilir).");
        return new ResponseEntity<>(response, HttpStatus.CONFLICT); // 409 Conflict döner
    }
}