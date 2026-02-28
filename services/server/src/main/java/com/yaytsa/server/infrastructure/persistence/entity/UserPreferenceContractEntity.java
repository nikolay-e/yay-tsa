package com.yaytsa.server.infrastructure.persistence.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "user_preference_contract")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UserPreferenceContractEntity {

  @Id
  @Column(name = "user_id")
  private UUID userId;

  @OneToOne(fetch = FetchType.LAZY)
  @MapsId
  @JoinColumn(name = "user_id", foreignKey = @ForeignKey(name = "fk_user_preference_contract_user"))
  private UserEntity user;

  @Column(name = "hard_rules", columnDefinition = "jsonb", nullable = false)
  private String hardRules = "{}";

  @Column(name = "soft_prefs", columnDefinition = "jsonb", nullable = false)
  private String softPrefs = "{}";

  @Column(name = "dj_style", columnDefinition = "jsonb", nullable = false)
  private String djStyle = "{}";

  @Column(name = "red_lines", columnDefinition = "jsonb", nullable = false)
  private String redLines = "[]";

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;
}
