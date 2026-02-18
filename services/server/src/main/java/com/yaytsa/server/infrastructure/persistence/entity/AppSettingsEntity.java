package com.yaytsa.server.infrastructure.persistence.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "app_settings")
@Getter
@Setter
@NoArgsConstructor
public class AppSettingsEntity {

  @Id
  @Column(name = "key")
  private String key;

  @Column(name = "value")
  private String value;

  @Column(name = "updated_at")
  private OffsetDateTime updatedAt;

  public AppSettingsEntity(String key, String value) {
    this.key = key;
    this.value = value;
    this.updatedAt = OffsetDateTime.now();
  }
}
