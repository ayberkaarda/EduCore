package com.educore.account;

import com.educore.security.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's own profile: {@code /api/v1/me} (any authenticated caller). Own enrollments are under
 * {@code /api/v1/me/enrollments} ({@code EnrollmentController}).
 */
@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    private final ProfileService profileService;

    public MeController(ProfileService profileService) {
        this.profileService = profileService;
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
}
