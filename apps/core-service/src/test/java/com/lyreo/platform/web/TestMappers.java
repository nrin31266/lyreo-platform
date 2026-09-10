package com.lyreo.platform.web;

import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

/**
 * Shared test fixture providing the Spring Boot production-equivalent Jackson 3 ObjectMapper.
 * Obtains the mapper configured with Boot's JacksonAutoConfiguration and JsonProblemDetailsConfiguration.
 */
public final class TestMappers {

    private static final ObjectMapper PRODUCTION_MAPPER;

    static {
        AtomicReference<ObjectMapper> ref = new AtomicReference<>();
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
            .run(context -> ref.set(context.getBean(ObjectMapper.class)));
        PRODUCTION_MAPPER = ref.get();
    }

    private TestMappers() {}

    public static ObjectMapper productionJsonMapper() {
        return PRODUCTION_MAPPER;
    }
}
