package com.educore.ingestion;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ImportedFileRepository extends JpaRepository<ImportedFile, Long> {

    /** The file with this content hash, locking its row until the transaction ends. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT f FROM ImportedFile f WHERE f.sha256 = :sha256")
    Optional<ImportedFile> findBySha256ForUpdate(@Param("sha256") String sha256);

    boolean existsBySha256AndStatusNot(String sha256, ImportedFile.Status status);

    /**
     * Marks PROCESSING files that no open job log references any more (their run was closed by recovery or
     * never got that far) as FAILED.
     */
    @Modifying
    @Query("UPDATE ImportedFile f SET f.status = com.educore.ingestion.ImportedFile.Status.FAILED "
            + "WHERE f.status = com.educore.ingestion.ImportedFile.Status.PROCESSING AND NOT EXISTS ("
            + "SELECT 1 FROM JobLog l WHERE l.importedFileId = f.id AND l.status IS NULL)")
    int failOrphans();
}
