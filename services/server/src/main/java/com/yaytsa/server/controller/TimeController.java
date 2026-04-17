package com.yaytsa.server.controller;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TimeController {

  @GetMapping(value = "/v1/time", produces = MediaType.TEXT_PLAIN_VALUE)
  public String getServerTime() {
    return Long.toString(System.currentTimeMillis());
  }
}
