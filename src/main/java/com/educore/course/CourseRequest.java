package com.educore.course;

import com.educore.common.validation.InputPatterns;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of course create/update (ADMIN): {@code name} (required, at most 150 characters, unique),
 * {@code term} (at most 50) and {@code instructor} (at most 100); all single-line text without control
 * characters. Any {@code id} in the body is ignored.
 * <p>
 * Public catalog fields (optional; {@code null} keeps the stored value, so clients that do not know them
 * never change them): {@code slug} (at most 80, lowercase letters and digits in hyphen-separated groups,
 * unique; generated from the name when the course has none), {@code description} (at most 1000, single
 * line; empty removes it) and {@code published} (only published courses appear in the public API).
 */
public record CourseRequest(
        @NotBlank @Size(max = 150) @Pattern(regexp = InputPatterns.SINGLE_LINE_TEXT) String name,
        @Size(max = 50) @Pattern(regexp = InputPatterns.SINGLE_LINE_TEXT) String term,
        @Size(max = 100) @Pattern(regexp = InputPatterns.SINGLE_LINE_TEXT) String instructor,
        @Size(max = 80) @Pattern(regexp = CourseSlugs.PATTERN) String slug,
        @Size(max = 1000) @Pattern(regexp = InputPatterns.SINGLE_LINE_TEXT) String description,
        Boolean published) {
}
