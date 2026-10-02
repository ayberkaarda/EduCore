package com.educore.ingestion.batch;

import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.validator.ValidationException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;

import java.util.TreeSet;

/**
 * A row that failed Bean Validation, reduced to the code {@code INVALID_FIELD:<sorted field names>}. The
 * {@link ValidationException} raised by {@code BeanValidatingItemProcessor} quotes the rejected values in its
 * message and cause; this replacement carries neither, so a skipped or failing row cannot put CSV values
 * into a log line or a job failure description.
 */
public class InvalidRowException extends ValidationException {

    private final String code;

    InvalidRowException(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }

    static InvalidRowException of(ValidationException e) {
        if (e.getCause() instanceof BindException bind) {
            TreeSet<String> fields = new TreeSet<>();
            for (FieldError error : bind.getFieldErrors()) {
                fields.add(error.getField());
            }
            if (!fields.isEmpty()) {
                return new InvalidRowException("INVALID_FIELD:" + String.join(",", fields));
            }
        }
        return new InvalidRowException("INVALID_ROW");
    }

    /** Wraps {@code validating} so that its failures become {@link InvalidRowException}s. */
    static <T> ItemProcessor<T, T> sanitizing(ItemProcessor<T, T> validating) {
        return item -> {
            try {
                return validating.process(item);
            } catch (InvalidRowException | DuplicateRowException e) {
                throw e;
            } catch (ValidationException e) {
                throw of(e);
            }
        };
    }
}
