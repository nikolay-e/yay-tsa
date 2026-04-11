package com.yaytsa.server.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.security.SecuritySchemes;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(
    info = @Info(title = "Yay-Tsa Media Server API", version = "1.0"),
    security = {@SecurityRequirement(name = "embyAuth"), @SecurityRequirement(name = "apiKey")})
@SecuritySchemes({
  @SecurityScheme(
      name = "embyAuth",
      type = SecuritySchemeType.APIKEY,
      in = SecuritySchemeIn.HEADER,
      paramName = "X-Emby-Authorization",
      description = "Emby-compatible auth header with Token parameter"),
  @SecurityScheme(
      name = "apiKey",
      type = SecuritySchemeType.APIKEY,
      in = SecuritySchemeIn.QUERY,
      paramName = "api_key",
      description = "API key query parameter for streaming endpoints")
})
public class OpenApiConfig {}
