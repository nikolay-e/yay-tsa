package com.yaytsa.server.config;

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jsr310.ser.OffsetDateTimeSerializer;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JacksonConfig {

  @Bean
  public Jackson2ObjectMapperBuilderCustomizer jsonCustomizer() {
    DateTimeFormatter formatter =
        new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd'T'HH:mm:ss")
            .appendFraction(ChronoField.MILLI_OF_SECOND, 0, 3, true)
            .appendOffsetId()
            .toFormatter();

    return builder -> {
      JavaTimeModule module = new JavaTimeModule();
      module.addSerializer(
          OffsetDateTime.class,
          new OffsetDateTimeSerializer(OffsetDateTimeSerializer.INSTANCE, false, formatter) {});
      builder.modules(module);
    };
  }
}
