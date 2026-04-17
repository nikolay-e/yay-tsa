package com.yaytsa.server.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "System", description = "System information and configuration")
public class TimeController {

  @Operation(
      summary = "Get server time",
      description = "Returns server timestamp in milliseconds for clock synchronization")
  @ApiResponse(responseCode = "200", description = "Server time in milliseconds as plain text")
  @SecurityRequirements
  @GetMapping(value = "/v1/time", produces = MediaType.TEXT_PLAIN_VALUE)
  public String getServerTime() {
    return Long.toString(System.currentTimeMillis());
  }
}
