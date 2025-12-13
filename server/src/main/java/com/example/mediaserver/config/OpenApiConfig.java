package com.example.mediaserver.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenAPI documentation configuration for the media server.
 * Provides API documentation compatible with Jellyfin clients.
 */
@Configuration
public class OpenApiConfig {

    @Value("${spring.application.name:yaytsa-media-server}")
    private String applicationName;

    @Value("${server.port:8080}")
    private String serverPort;

    @Bean
    public OpenAPI customOpenAPI() {
        final String securitySchemeName = "bearerAuth";
        final String apiKeyScheme = "apiKey";

        return new OpenAPI()
            .info(new Info()
                .title("Yaytsa Media Server API")
                .version("0.1.0")
                .description("A Jellyfin-compatible media server API for streaming music collections")
                .contact(new Contact()
                    .name("Yaytsa Team")
                    .url("https://github.com/yourusername/yaytsa"))
                .license(new License()
                    .name("MIT")
                    .url("https://opensource.org/licenses/MIT")))
            .servers(List.of(
                new Server()
                    .url("http://localhost:" + serverPort)
                    .description("Development server"),
                new Server()
                    .url("https://api.yaytsa.local")
                    .description("Production server")))
            .addSecurityItem(new SecurityRequirement()
                .addList(securitySchemeName)
                .addList(apiKeyScheme))
            .components(new Components()
                .addSecuritySchemes(securitySchemeName, new SecurityScheme()
                    .name(securitySchemeName)
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("Token")
                    .description("Use the token received from /Users/AuthenticateByName"))
                .addSecuritySchemes(apiKeyScheme, new SecurityScheme()
                    .name("api_key")
                    .type(SecurityScheme.Type.APIKEY)
                    .in(SecurityScheme.In.QUERY)
                    .description("API key for authentication (used in streaming URLs)")));
    }
}
