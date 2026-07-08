package com.example.project3.dto;
import com.example.project3.entity.Role;
public record AccountDTO(Long id, String firstName, String lastName, String studentNumber, Role role) {}
