package com.yaytsa.server.dto.response;

import java.util.List;

public record RadioResponse(List<BaseItemResponse> Items, int TotalRecordCount) {}
