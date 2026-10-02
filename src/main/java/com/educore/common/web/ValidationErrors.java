package com.educore.common.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.ElementKind;
import jakarta.validation.Path;
import org.springframework.beans.TypeMismatchException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.Errors;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MissingRequestValueException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Turns the request-validation exceptions of Spring MVC, Bean Validation and Jackson into the
 * {@code errors[{field, code}]} member of a 400 problem. Only field names and stable codes are produced:
 * rejected values and exception messages never reach the client.
 */
public final class ValidationErrors {

    /** The field name used when the request body as a whole is missing or unreadable. */
    public static final String BODY = "body";

    private static final Map<String, String> CONSTRAINT_CODES = Map.ofEntries(
            Map.entry("NotNull", "required"),
            Map.entry("NotBlank", "required"),
            Map.entry("NotEmpty", "required"),
            Map.entry("Size", "size"),
            Map.entry("Length", "size"),
            Map.entry("Pattern", "pattern"),
            Map.entry("Email", "pattern"),
            Map.entry("Min", "range"),
            Map.entry("Max", "range"),
            Map.entry("DecimalMin", "range"),
            Map.entry("DecimalMax", "range"),
            Map.entry("Positive", "range"),
            Map.entry("PositiveOrZero", "range"),
            Map.entry("Negative", "range"),
            Map.entry("NegativeOrZero", "range"),
            Map.entry("typeMismatch", "type"));

    private ValidationErrors() {
    }

    /** The violations described by {@code exception}; empty when it carries no field information. */
    public static List<FieldViolation> of(Throwable exception) {
        TreeSet<FieldViolation> violations = new TreeSet<>();
        if (exception instanceof BindException bind) {
            addBindingResult(bind.getBindingResult(), "", violations);
        } else if (exception instanceof HandlerMethodValidationException validation) {
            for (ParameterValidationResult result : validation.getParameterValidationResults()) {
                addParameterResult(result, violations);
            }
        } else if (exception instanceof ConstraintViolationException constraints) {
            for (ConstraintViolation<?> violation : constraints.getConstraintViolations()) {
                violations.add(new FieldViolation(fieldOf(violation.getPropertyPath()),
                        code(violation.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName())));
            }
        } else if (exception instanceof MethodArgumentTypeMismatchException mismatch) {
            violations.add(new FieldViolation(mismatch.getName(), "type"));
        } else if (exception instanceof TypeMismatchException mismatch && mismatch.getPropertyName() != null) {
            violations.add(new FieldViolation(mismatch.getPropertyName(), "type"));
        } else if (exception instanceof MissingServletRequestParameterException missing) {
            violations.add(new FieldViolation(missing.getParameterName(), "required"));
        } else if (exception instanceof MissingRequestValueException) {
            violations.add(new FieldViolation("request", "required"));
        } else if (exception instanceof HttpMessageNotReadableException unreadable) {
            violations.add(unreadable(unreadable));
        }
        return List.copyOf(violations);
    }

    /** The stable code for a constraint annotation name or binding error code. */
    static String code(String constraintName) {
        return CONSTRAINT_CODES.getOrDefault(constraintName, "invalid");
    }

    private static void addParameterResult(ParameterValidationResult result, TreeSet<FieldViolation> violations) {
        if (result instanceof ParameterErrors errors) {
            addBindingResult(errors, "", violations);
            return;
        }
        String name = result.getMethodParameter().getParameterName();
        String field = name == null ? "request" : name;
        for (MessageSourceResolvable error : result.getResolvableErrors()) {
            violations.add(new FieldViolation(field, code(lastCode(error))));
        }
    }

    private static void addBindingResult(Errors result, String prefix, TreeSet<FieldViolation> violations) {
        for (FieldError error : result.getFieldErrors()) {
            violations.add(new FieldViolation(prefix + error.getField(), code(error.getCode())));
        }
        result.getGlobalErrors().forEach(error -> violations.add(new FieldViolation(BODY, code(error.getCode()))));
    }

    private static String lastCode(MessageSourceResolvable error) {
        String[] codes = error.getCodes();
        return codes == null || codes.length == 0 ? "" : codes[codes.length - 1];
    }

    /**
     * For method-level validation the path is {@code method.parameter[...]...}; the parameter node (and any
     * bean property below it) names the field. Container element nodes ({@code <list element>}) are dropped.
     */
    private static String fieldOf(Path path) {
        List<String> names = new ArrayList<>();
        for (Path.Node node : path) {
            ElementKind kind = node.getKind();
            if (kind == ElementKind.PARAMETER || kind == ElementKind.PROPERTY) {
                names.add(node.getName());
            }
        }
        return names.isEmpty() ? "request" : String.join(".", names);
    }

    private static FieldViolation unreadable(HttpMessageNotReadableException exception) {
        Throwable cause = exception.getCause();
        if (cause instanceof InvalidFormatException format) {
            String field = jsonPath(format);
            boolean isEnum = format.getTargetType() != null && format.getTargetType().isEnum();
            return new FieldViolation(field, isEnum ? "enum" : "type");
        }
        if (cause instanceof MismatchedInputException mismatch) {
            return new FieldViolation(jsonPath(mismatch), "type");
        }
        if (cause instanceof JsonMappingException mapping && !mapping.getPath().isEmpty()) {
            return new FieldViolation(jsonPath(mapping), "invalid");
        }
        if (cause instanceof JsonProcessingException) {
            return new FieldViolation(BODY, "malformed");
        }
        return new FieldViolation(BODY, "required");
    }

    private static String jsonPath(JsonMappingException exception) {
        StringBuilder path = new StringBuilder();
        for (JsonMappingException.Reference reference : exception.getPath()) {
            if (reference.getFieldName() != null) {
                if (!path.isEmpty()) {
                    path.append('.');
                }
                path.append(reference.getFieldName());
            } else if (reference.getIndex() >= 0) {
                path.append('[').append(reference.getIndex()).append(']');
            }
        }
        return path.isEmpty() ? BODY : path.toString();
    }
}
