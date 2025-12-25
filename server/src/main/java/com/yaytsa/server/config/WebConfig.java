package com.yaytsa.server.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.ContentNegotiationConfigurer;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC configuration for the media server.
 * Configures content negotiation, async support, and static resource handling.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${yaytsa.media.streaming.buffer-size:65536}")
    private int streamingBufferSize;

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        // Configure async request timeout for long-running operations like transcoding
        configurer.setDefaultTimeout(3600000); // 1 hour
    }

    @Override
    public void configureContentNegotiation(ContentNegotiationConfigurer configurer) {
        configurer
            .favorParameter(true)
            .parameterName("mediaType")
            .ignoreAcceptHeader(false)
            .useRegisteredExtensionsOnly(false)
            .defaultContentType(org.springframework.http.MediaType.APPLICATION_JSON);
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Add resource handlers if needed for serving static content
        // This might be used for serving album artwork or other static assets
    }

    /**
     * Register custom argument resolvers, interceptors, etc. as needed
     */
}
