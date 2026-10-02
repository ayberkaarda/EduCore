package com.educore.common.logging;

import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;

import java.util.Set;

/**
 * Structured (JSON) logging customizer, registered with {@code logging.structured.json.customizer}: every
 * string value of the log event (message, exception message, stack trace, MDC and key-value pairs) passes
 * through {@link PiiMasking}. The correlation members {@code requestId} and {@code userId} are emitted
 * unchanged: they are validated ids, and masking them would break log correlation.
 */
public class PiiMaskingJsonCustomizer implements StructuredLoggingJsonMembersCustomizer<Object> {

    private static final Set<String> UNMASKED = Set.of(MdcKeys.REQUEST_ID, MdcKeys.USER_ID);

    @Override
    public void customize(JsonWriter.Members<Object> members) {
        JsonWriter.ValueProcessor<String> masking = (path, value) ->
                path != null && UNMASKED.contains(path.name()) ? value : PiiMasking.mask(value);
        members.applyingValueProcessor(masking.whenInstanceOf(String.class));
    }
}
