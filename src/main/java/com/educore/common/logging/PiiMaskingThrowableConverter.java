package com.educore.common.logging;

import ch.qos.logback.classic.spi.IThrowableProxy;
import org.springframework.boot.logging.logback.ExtendedWhitespaceThrowableProxyConverter;

/**
 * Spring Boot's {@code %wEx} stack trace converter with {@link PiiMasking} applied to the rendered trace, so
 * exception messages (which can quote SQL parameters, e-mail addresses or tokens) are masked like the log
 * message. Registered for {@code %wEx} in {@code logback-spring.xml}.
 */
public class PiiMaskingThrowableConverter extends ExtendedWhitespaceThrowableProxyConverter {

    @Override
    protected String throwableProxyToString(IThrowableProxy throwableProxy) {
        return PiiMasking.mask(super.throwableProxyToString(throwableProxy));
    }
}
