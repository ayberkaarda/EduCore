package com.educore.ingestion;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Manual CSV upload: {@code POST /api/v1/admin/imports} (ADMIN, {@code multipart/form-data} with part
 * {@code file}). The file is validated like an inbox file, written into {@code inbox/} and imported by the
 * poller; the answer is {@code 202 Accepted} and the outcome appears in the job logs.
 */
@RestController
@RequestMapping("/api/v1/admin/imports")
@PreAuthorize("hasRole('ADMIN')")
@Validated
public class ImportAdminController {

    private final ImportUploadService uploadService;

    public ImportAdminController(ImportUploadService uploadService) {
        this.uploadService = uploadService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImportAcceptedResponse> upload(@RequestPart("file") MultipartFile file) {
        return ResponseEntity.accepted().body(uploadService.accept(file));
    }
}
