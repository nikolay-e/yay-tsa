package com.yaytsa.server.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.security.SecuritySchemes;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(
    info = @Info(title = "Yay-Tsa Media Server API", version = "1.0"),
    security = {@SecurityRequirement(name = "bearerAuth")})
@SecuritySchemes({
  @SecurityScheme(
      name = "bearerAuth",
      type = SecuritySchemeType.HTTP,
      scheme = "bearer",
      description = "Opaque token obtained from /Users/AuthenticateByName"),
  @SecurityScheme(
      name = "apiKey",
      type = SecuritySchemeType.APIKEY,
      in = SecuritySchemeIn.QUERY,
      paramName = "api_key",
      description = "Token as query parameter for streaming endpoints (browser limitation)")
})
public class OpenApiConfig {

  private static final Set<String> PUBLIC_PATHS =
      Set.of("/Users/AuthenticateByName", "/System/Info/Public");

  @Bean
  public OpenApiCustomizer globalErrorResponseCustomizer() {
    return openApi -> {
      Schema<?> problemSchema =
          new Schema<>()
              .type("object")
              .addProperty("type", new Schema<>().type("string"))
              .addProperty("title", new Schema<>().type("string"))
              .addProperty("status", new Schema<>().type("integer"))
              .addProperty("detail", new Schema<>().type("string"))
              .addProperty("path", new Schema<>().type("string"))
              .addProperty("timestamp", new Schema<>().type("string"));

      openApi.getComponents().addSchemas("ProblemDetail", problemSchema);

      Content errorContent =
          new Content()
              .addMediaType(
                  "application/json",
                  new MediaType()
                      .schema(new Schema<>().$ref("#/components/schemas/ProblemDetail")));

      ApiResponse badRequest = new ApiResponse().description("Bad request").content(errorContent);
      ApiResponse unauthorized =
          new ApiResponse().description("Unauthorized").content(errorContent);
      ApiResponse forbidden = new ApiResponse().description("Forbidden").content(errorContent);

      if (openApi.getPaths() == null) return;

      openApi
          .getPaths()
          .forEach(
              (path, pathItem) -> {
                if (PUBLIC_PATHS.contains(path)) return;

                pathItem
                    .readOperations()
                    .forEach(
                        operation -> {
                          if (operation.getResponses() == null) return;
                          operation.getResponses().putIfAbsent("400", badRequest);
                          operation.getResponses().put("401", unauthorized);
                          operation.getResponses().put("403", forbidden);
                        });
              });
    };
  }
}
