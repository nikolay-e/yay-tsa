package com.yaytsa.server.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

public record UpdateSessionStateRequest(@JsonProperty("state") Map<String, Object> state) {}
