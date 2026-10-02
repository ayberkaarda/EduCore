package com.educore.common.logging;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * Logback converter for the human-readable pattern: the formatted message (with its arguments substituted)
 * passed through {@link PiiMasking}. {@code logback-spring.xml} registers it for {@code %m}, {@code %msg} and
 * {@code %message}, so the default Spring Boot pattern is masked without being redefined.
 */
public class PiiMaskingConverter extends MessageConverter {

    @Override
    public String convert(ILoggingEvent event) {
        return PiiMasking.mask(super.convert(event));
    }
}
