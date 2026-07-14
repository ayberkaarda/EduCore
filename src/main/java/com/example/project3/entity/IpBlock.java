package com.example.project3.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IpBlock {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String type; // STATIC, RANGE, CIDR
    private String originalValue; // Örn: "192.168.1.1-192.168.1.255" veya "10.0.0.0/24"

    // Arka planda kontroller için kullanılacak sayısal değerler
    private Long startIp;
    private Long endIp;
}