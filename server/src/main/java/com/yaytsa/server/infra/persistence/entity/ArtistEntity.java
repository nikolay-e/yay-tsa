package com.yaytsa.server.infra.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "artists")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ArtistEntity {

    @Id
    @Column(name = "item_id")
    private UUID itemId;

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "item_id", foreignKey = @ForeignKey(name = "fk_artists_item"))
    private ItemEntity item;

    @Column(name = "musicbrainz_id", length = 36)
    private String musicbrainzId;

    @Column(columnDefinition = "TEXT")
    private String biography;

    @Column(name = "formed_date")
    private LocalDate formedDate;

    @Column(name = "ended_date")
    private LocalDate endedDate;
}
