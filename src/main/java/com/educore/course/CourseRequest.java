package com.educore.course;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of course create/update (ADMIN). Any {@code id} in the body is ignored. */
public record CourseRequest(@NotBlank @Size(max = 255) String name,
                            @Size(max = 255) String term,
                            @Size(max = 255) String instructor) {
}
