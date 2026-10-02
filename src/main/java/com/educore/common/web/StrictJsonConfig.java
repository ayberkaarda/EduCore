package com.educore.common.web;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Strict JSON binding for request bodies (the application {@link ObjectMapper}): a value must already have
 * the declared JSON type. Rejected with 400 {@code request/invalid}:
 * <ul>
 *   <li>numbers for enums ({@code {"role": 0}} would otherwise be the first constant, {@code ADMIN});</li>
 *   <li>fractions for integers ({@code {"courseId": 1.9}} would otherwise be truncated to 1);</li>
 *   <li>strings for numbers or booleans ({@code "5"}, {@code "true"}), and numbers or booleans for strings;</li>
 *   <li>{@code null} for primitives, and anything after the first JSON value ({@code {...} {...}}).</li>
 * </ul>
 * Unknown properties stay ignored (server-owned fields in a body are dropped, see {@code ROUTES.md}).
 */
@Configuration(proxyBeanMethods = false)
public class StrictJsonConfig {

    @Bean
    Jackson2ObjectMapperBuilderCustomizer strictJsonBinding() {
        return builder -> builder
                .featuresToEnable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS,
                        DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                        DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .featuresToDisable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .postConfigurer(StrictJsonConfig::rejectScalarCoercion);
    }

    static void rejectScalarCoercion(ObjectMapper mapper) {
        mapper.coercionConfigFor(LogicalType.Integer)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        mapper.coercionConfigFor(LogicalType.Float)
                .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        mapper.coercionConfigFor(LogicalType.Boolean)
                .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail);
        mapper.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        mapper.coercionConfigFor(LogicalType.Enum)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail);
    }
}
