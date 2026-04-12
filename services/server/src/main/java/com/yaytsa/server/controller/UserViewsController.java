package com.yaytsa.server.controller;

import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UserViewsController {

  @GetMapping({"/UserViews", "/Users/{userId}/Views"})
  public ResponseEntity<Map<String, Object>> getUserViews() {
    var musicLibrary =
        Map.of(
            "Id", "music-library",
            "Name", "Music",
            "Type", "CollectionFolder",
            "CollectionType", "music",
            "ServerId", "yaytsa-server",
            "IsFolder", true);
    return ResponseEntity.ok(Map.of("Items", List.of(musicLibrary), "TotalRecordCount", 1));
  }
}
