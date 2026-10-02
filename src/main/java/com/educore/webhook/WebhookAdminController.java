package com.educore.webhook;

import com.educore.common.web.PageResponse;
import com.educore.common.web.Paging;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Outbound webhook subscriptions: {@code /api/v1/admin/webhooks} (ADMIN). See docs/integrations/WEBHOOKS.md.
 */
@RestController
@RequestMapping("/api/v1/admin/webhooks")
@PreAuthorize("hasRole('ADMIN')")
@Validated
public class WebhookAdminController {

    private final WebhookService webhookService;

    public WebhookAdminController(WebhookService webhookService) {
        this.webhookService = webhookService;
    }

    @GetMapping
    public List<WebhookResponse> list() {
        return webhookService.list();
    }

    @PostMapping
    public ResponseEntity<WebhookCreatedResponse> create(@Valid @RequestBody WebhookRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(webhookService.create(request));
    }

    @GetMapping("/{webhookId}")
    public WebhookResponse get(@PathVariable @Positive long webhookId) {
        return webhookService.get(webhookId);
    }

    @PutMapping("/{webhookId}")
    public WebhookResponse update(@PathVariable @Positive long webhookId, @Valid @RequestBody WebhookRequest request) {
        return webhookService.update(webhookId, request);
    }

    @DeleteMapping("/{webhookId}")
    public ResponseEntity<Void> delete(@PathVariable @Positive long webhookId) {
        webhookService.delete(webhookId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{webhookId}/test")
    public ResponseEntity<TestDeliveryResponse> sendTest(@PathVariable @Positive long webhookId) {
        return ResponseEntity.accepted().body(webhookService.sendTest(webhookId));
    }

    @GetMapping("/{webhookId}/deliveries")
    public PageResponse<WebhookDeliveryResponse> deliveries(
            @PathVariable @Positive long webhookId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(Paging.MAX_SIZE) int size) {
        return webhookService.deliveries(webhookId, page, size);
    }
}
