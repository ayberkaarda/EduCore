package com.educore.account;

import com.educore.auth.AuthResponse;
import com.educore.auth.AuthService;
import com.educore.auth.ClientInfo;
import com.educore.auth.RefreshCookies;
import com.educore.lifecycle.AccountExport;
import com.educore.lifecycle.AccountLifecycleService;
import com.educore.lifecycle.DataExportService;
import com.educore.lifecycle.DeleteAccountRequest;
import com.educore.lifecycle.DeletionScheduledResponse;
import com.educore.lifecycle.RestoreAccountRequest;
import com.educore.security.AuthenticatedUser;
import com.educore.security.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * The caller's own account: {@code /api/v1/me} (any authenticated caller). Own enrollments are under
 * {@code /api/v1/me/enrollments} ({@code EnrollmentController}).
 * <ul>
 *   <li>{@code GET /me}, {@code PUT /me}: profile;</li>
 *   <li>{@code DELETE /me} {@code {currentPassword}}: request deletion (202, 30-day grace period, every
 *       session revoked, refresh cookie cleared);</li>
 *   <li>{@code POST /me/restore} {@code {currentPassword}}: cancel a pending deletion inside the grace period
 *       (new session: access token and refresh cookie, every earlier session ended);</li>
 *   <li>{@code GET /me/export}: data export as a JSON attachment (1 per minute).</li>
 * </ul>
 * During the grace period only {@code GET /me}, {@code POST /me/restore} and logout are allowed
 * ({@code PendingDeletionScopeFilter}).
 */
@RestController
@RequestMapping("/api/v1/me")
@Validated
public class MeController {

    private static final DateTimeFormatter EXPORT_DATE = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private final ProfileService profileService;
    private final AccountLifecycleService lifecycleService;
    private final DataExportService dataExportService;
    private final RefreshCookies refreshCookies;
    private final ClientIpResolver clientIpResolver;

    public MeController(ProfileService profileService, AccountLifecycleService lifecycleService,
                        DataExportService dataExportService, RefreshCookies refreshCookies,
                        ClientIpResolver clientIpResolver) {
        this.profileService = profileService;
        this.lifecycleService = lifecycleService;
        this.dataExportService = dataExportService;
        this.refreshCookies = refreshCookies;
        this.clientIpResolver = clientIpResolver;
    }

    @GetMapping
    public ProfileResponse profile(@AuthenticationPrincipal AuthenticatedUser user) {
        return profileService.get(user);
    }

    @PutMapping
    public ProfileResponse updateProfile(@AuthenticationPrincipal AuthenticatedUser user,
                                         @Valid @RequestBody UpdateProfileRequest request) {
        return profileService.update(user, request);
    }

    @DeleteMapping
    public ResponseEntity<DeletionScheduledResponse> requestDeletion(@AuthenticationPrincipal AuthenticatedUser user,
                                                                     @Valid @RequestBody DeleteAccountRequest request,
                                                                     HttpServletRequest http) {
        ClientInfo client = new ClientInfo(clientIpResolver.resolve(http), http.getHeader(HttpHeaders.USER_AGENT));
        DeletionScheduledResponse scheduled = lifecycleService.requestDeletion(user, request, client);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, refreshCookies.clear().toString())
                .body(scheduled);
    }

    /**
     * Cancels a pending deletion with the current password; the answer is a new session exactly like a login
     * (access token in the body, new refresh cookie): every earlier session of the account has ended.
     */
    @PostMapping("/restore")
    public ResponseEntity<AuthResponse> restore(@AuthenticationPrincipal AuthenticatedUser user,
                                                @Valid @RequestBody RestoreAccountRequest request,
                                                HttpServletRequest http) {
        ClientInfo client = new ClientInfo(clientIpResolver.resolve(http), http.getHeader(HttpHeaders.USER_AGENT));
        AuthService.Session session = lifecycleService.restoreOwn(user, request, client);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, refreshCookies.issue(session.refreshToken()).toString())
                .body(session.body());
    }

    @GetMapping("/export")
    public ResponseEntity<AccountExport> export(@AuthenticationPrincipal AuthenticatedUser user) {
        AccountExport export = dataExportService.export(user);
        String fileName = "educore-account-export-" + EXPORT_DATE.format(export.exportedAt()) + ".json";
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(fileName).build()
                        .toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body(export);
    }
}
