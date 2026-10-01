package com.educore.repository;
import com.educore.entity.IpBlock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface IpBlockRepository extends JpaRepository<IpBlock, Long> {
}