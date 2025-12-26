package com.yaytsa.server.infrastructure.persistence.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Inheritance(strategy = InheritanceType.JOINED)
public class ItemEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  @Column(nullable = false, length = 50)
  private ItemType type;

  @Column(nullable = false, length = 500)
  private String name;

  @Column(name = "sort_name", length = 500)
  private String sortName;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "parent_id", foreignKey = @ForeignKey(name = "fk_parent"))
  private ItemEntity parent;

  @OneToMany(mappedBy = "parent", cascade = CascadeType.ALL, orphanRemoval = true)
  private List<ItemEntity> children = new ArrayList<>();

  @Column(unique = true, columnDefinition = "TEXT")
  private String path;

  @Column(length = 50)
  private String container;

  @Column(name = "size_bytes")
  private Long sizeBytes;

  @Column private OffsetDateTime mtime;

  @Column(name = "library_root", columnDefinition = "TEXT")
  private String libraryRoot;

  @Column(columnDefinition = "TEXT")
  private String overview;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private OffsetDateTime createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  @OneToMany(
      mappedBy = "item",
      cascade = CascadeType.ALL,
      orphanRemoval = true,
      fetch = FetchType.EAGER)
  private List<ItemGenreEntity> itemGenres = new ArrayList<>();

  @OneToMany(
      mappedBy = "item",
      cascade = CascadeType.ALL,
      orphanRemoval = true,
      fetch = FetchType.EAGER)
  private List<ImageEntity> images = new ArrayList<>();
}
