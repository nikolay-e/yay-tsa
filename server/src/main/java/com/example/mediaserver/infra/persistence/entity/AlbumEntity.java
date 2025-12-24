package com.example.mediaserver.infra.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "albums")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AlbumEntity {

    @Id
    @Column(name = "item_id")
    private UUID itemId;

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "item_id", foreignKey = @ForeignKey(name = "fk_albums_item"))
    private ItemEntity item;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "artist_id", foreignKey = @ForeignKey(name = "fk_albums_artist"))
    private ItemEntity artist;

    @Column(name = "release_date")
    private LocalDate releaseDate;

    @Column(name = "total_tracks")
    private Integer totalTracks;

    @Column(name = "total_discs")
    private Integer totalDiscs = 1;
}
