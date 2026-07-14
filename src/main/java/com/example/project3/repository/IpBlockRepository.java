package com.example.project3.repository;
import com.example.project3.entity.IpBlock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface IpBlockRepository extends JpaRepository<IpBlock, Long> {
}