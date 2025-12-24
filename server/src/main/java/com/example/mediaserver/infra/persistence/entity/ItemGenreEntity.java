package com.example.mediaserver.infra.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "item_genres")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ItemGenreEntity {

    @EmbeddedId
    private ItemGenreId id;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("itemId")
    @JoinColumn(name = "item_id", foreignKey = @ForeignKey(name = "fk_item_genres_item"))
    private ItemEntity item;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("genreId")
    @JoinColumn(name = "genre_id", foreignKey = @ForeignKey(name = "fk_item_genres_genre"))
    private GenreEntity genre;

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ItemGenreId implements Serializable {
        @Column(name = "item_id")
        private UUID itemId;

        @Column(name = "genre_id")
        private UUID genreId;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ItemGenreId that = (ItemGenreId) o;
            return Objects.equals(itemId, that.itemId) && Objects.equals(genreId, that.genreId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(itemId, genreId);
        }
    }
}
