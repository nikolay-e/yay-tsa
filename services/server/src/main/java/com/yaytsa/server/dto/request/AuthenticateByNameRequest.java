package com.yaytsa.server.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public record AuthenticateByNameRequest(
    @JsonProperty("Username") @NotBlank(message = "Username is required") String username,
    @JsonProperty("Pw") @NotBlank(message = "Password is required") String password) {}
