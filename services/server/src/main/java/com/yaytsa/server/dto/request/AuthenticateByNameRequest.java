package com.yaytsa.server.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record AuthenticateByNameRequest(
    @JsonProperty("Username")
        @NotBlank(message = "Username is required")
        @Pattern(regexp = "^[^\u0000]+$", message = "Username contains invalid characters")
        String username,
    @JsonProperty("Pw") @NotBlank(message = "Password is required") String password) {}
