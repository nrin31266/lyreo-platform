package com.lyreo.vocabulary.infrastructure.persistence;

import com.lyreo.contracts.errors.ResourceNotFoundException;
import com.lyreo.contracts.errors.StateConflictException;
import com.lyreo.vocabulary.application.port.VocabularyCollectionRepository;
import com.lyreo.vocabulary.domain.VocabularyCard;
import com.lyreo.vocabulary.domain.VocabularyCollection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** PostgreSQL implementation of {@link VocabularyCollectionRepository}. */
public class JdbcVocabularyCollectionRepository implements VocabularyCollectionRepository {
    private static final String DEFAULT_NAME = "Đã lưu";
    private static final String DEFAULT_PRESET = "BLUE_BOOK";

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcVocabularyCollectionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public VocabularyCollection ensureDefaultCollection(UUID learnerId) {
        jdbc.update("""
            INSERT INTO vocabulary_collection (id, learner_id, name, is_default, cover_preset)
            VALUES (:id, :learnerId, :name, true, :preset)
            ON CONFLICT (learner_id) WHERE is_default DO NOTHING
            """, Map.of(
            "id", UUID.randomUUID(),
            "learnerId", learnerId,
            "name", DEFAULT_NAME,
            "preset", DEFAULT_PRESET
        ));
        return jdbc.query("""
            SELECT col.id, col.learner_id, col.name, col.is_default, col.cover_preset,
                   col.created_at, col.updated_at, count(ci.card_id) AS item_count
            FROM vocabulary_collection col
            LEFT JOIN vocabulary_collection_item ci ON ci.collection_id = col.id
            WHERE col.learner_id = :learnerId AND col.is_default = true
            GROUP BY col.id
            """, Map.of("learnerId", learnerId), JdbcVocabularyCollectionRepository::mapCollection)
            .stream().findFirst().orElseThrow();
    }

    @Override
    public List<VocabularyCollection> listCollections(UUID learnerId) {
        return jdbc.query("""
            SELECT col.id, col.learner_id, col.name, col.is_default, col.cover_preset,
                   col.created_at, col.updated_at, count(ci.card_id) AS item_count
            FROM vocabulary_collection col
            LEFT JOIN vocabulary_collection_item ci ON ci.collection_id = col.id
            WHERE col.learner_id = :learnerId
            GROUP BY col.id
            ORDER BY col.is_default DESC, col.created_at ASC, col.name ASC
            """, Map.of("learnerId", learnerId), JdbcVocabularyCollectionRepository::mapCollection);
    }

    @Override
    public Optional<VocabularyCollection> findCollection(UUID learnerId, UUID collectionId) {
        return jdbc.query("""
            SELECT col.id, col.learner_id, col.name, col.is_default, col.cover_preset,
                   col.created_at, col.updated_at, count(ci.card_id) AS item_count
            FROM vocabulary_collection col
            LEFT JOIN vocabulary_collection_item ci ON ci.collection_id = col.id
            WHERE col.id = :id AND col.learner_id = :learnerId
            GROUP BY col.id
            """, Map.of("id", collectionId, "learnerId", learnerId), JdbcVocabularyCollectionRepository::mapCollection)
            .stream().findFirst();
    }

    @Override
    public VocabularyCollection createCollection(UUID learnerId, String name, String coverPreset) {
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("""
                INSERT INTO vocabulary_collection (id, learner_id, name, is_default, cover_preset)
                VALUES (:id, :learnerId, :name, false, :preset)
                """, Map.of(
                "id", id,
                "learnerId", learnerId,
                "name", name,
                "preset", coverPreset
            ));
        } catch (DuplicateKeyException ex) {
            throw new StateConflictException("A collection named '" + name + "' already exists");
        }
        return findCollection(learnerId, id).orElseThrow();
    }

    @Override
    public VocabularyCollection updateCollection(UUID learnerId, UUID collectionId, String name, String coverPreset) {
        try {
            int updated = jdbc.update("""
                UPDATE vocabulary_collection
                SET name = :name, cover_preset = :preset, updated_at = now()
                WHERE id = :id AND learner_id = :learnerId
                """, Map.of(
                "id", collectionId,
                "learnerId", learnerId,
                "name", name,
                "preset", coverPreset
            ));
            if (updated != 1) {
                throw new ResourceNotFoundException("Vocabulary collection not found");
            }
        } catch (DuplicateKeyException ex) {
            throw new StateConflictException("A collection named '" + name + "' already exists");
        }
        return findCollection(learnerId, collectionId).orElseThrow();
    }

    @Override
    public void deleteCollectionMovingOrphans(UUID learnerId, UUID collectionId, UUID defaultCollectionId) {
        jdbc.update("""
            INSERT INTO vocabulary_collection_item (collection_id, card_id)
            SELECT :defaultId, ci.card_id
            FROM vocabulary_collection_item ci
            WHERE ci.collection_id = :collectionId
              AND NOT EXISTS (
                  SELECT 1 FROM vocabulary_collection_item other
                  WHERE other.card_id = ci.card_id AND other.collection_id <> :collectionId
              )
            ON CONFLICT DO NOTHING
            """, Map.of("defaultId", defaultCollectionId, "collectionId", collectionId));

        int deleted = jdbc.update("""
            DELETE FROM vocabulary_collection
            WHERE id = :collectionId AND learner_id = :learnerId AND NOT is_default
            """, Map.of("collectionId", collectionId, "learnerId", learnerId));
        if (deleted != 1) {
            throw new ResourceNotFoundException("Vocabulary collection not found");
        }
    }

    @Override
    public CollectionItemPage listItems(UUID learnerId, UUID collectionId, String query, int page, int size) {
        String pattern = "%" + query.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        var params = new MapSqlParameterSource()
            .addValue("collectionId", collectionId)
            .addValue("learnerId", learnerId)
            .addValue("query", query)
            .addValue("pattern", pattern)
            .addValue("limit", size)
            .addValue("offset", page * size);

        String whereClause = """
            FROM vocabulary_collection_item ci
            JOIN vocabulary_card c ON c.id = ci.card_id
            WHERE ci.collection_id = :collectionId
              AND c.learner_id = :learnerId
              AND (:query = ''
                   OR c.term ILIKE :pattern ESCAPE '!'
                   OR COALESCE(c.meaning_vi, '') ILIKE :pattern ESCAPE '!'
                   OR COALESCE(c.definition_en, '') ILIKE :pattern ESCAPE '!'
                   OR COALESCE(c.context_sentence, '') ILIKE :pattern ESCAPE '!')
            """;

        Long totalObj = jdbc.queryForObject("SELECT count(*) " + whereClause, params, Long.class);
        long total = totalObj == null ? 0L : totalObj;

        List<RowWithAddedAt> rows = jdbc.query("""
            SELECT c.*, ci.added_at
            """ + whereClause + """
            ORDER BY ci.added_at DESC, c.id
            LIMIT :limit OFFSET :offset
            """, params, (rs, n) -> new RowWithAddedAt(
            JdbcVocabularyRepository.mapRow(rs),
            rs.getTimestamp("added_at").toInstant()
        ));

        Map<UUID, List<UUID>> memberships = loadMemberships(rows.stream().map(r -> r.card().id()).toList());
        List<CollectionItemRow> items = rows.stream()
            .map(r -> new CollectionItemRow(
                r.card(),
                r.addedAt(),
                memberships.getOrDefault(r.card().id(), List.of())
            ))
            .toList();
        boolean hasMore = ((long) (page + 1) * size) < total;
        return new CollectionItemPage(items, page, size, total, hasMore);
    }

    @Override
    public void attachCardToCollections(UUID cardId, List<UUID> collectionIds) {
        for (UUID colId : collectionIds) {
            jdbc.update("""
                INSERT INTO vocabulary_collection_item (collection_id, card_id)
                VALUES (:collectionId, :cardId)
                ON CONFLICT DO NOTHING
                """, Map.of("collectionId", colId, "cardId", cardId));
        }
    }

    @Override
    public void addItemsToCollection(UUID learnerId, UUID collectionId, List<UUID> cardIds) {
        for (UUID cardId : cardIds) {
            jdbc.update("""
                INSERT INTO vocabulary_collection_item (collection_id, card_id)
                SELECT col.id, c.id
                FROM vocabulary_collection col
                JOIN vocabulary_card c ON c.id = :cardId AND c.learner_id = :learnerId
                WHERE col.id = :collectionId AND col.learner_id = :learnerId
                ON CONFLICT DO NOTHING
                """, Map.of("collectionId", collectionId, "cardId", cardId, "learnerId", learnerId));
        }
    }

    @Override
    public boolean verifyAllCollectionsOwned(UUID learnerId, List<UUID> collectionIds) {
        if (collectionIds.isEmpty()) {
            return true;
        }
        List<UUID> distinct = collectionIds.stream().distinct().toList();
        Integer count = jdbc.queryForObject("""
            SELECT count(*) FROM vocabulary_collection
            WHERE learner_id = :learnerId AND id IN (:ids)
            """, Map.of("learnerId", learnerId, "ids", distinct), Integer.class);
        return count != null && count == distinct.size();
    }

    @Override
    public boolean verifyAllCardsOwned(UUID learnerId, List<UUID> cardIds) {
        if (cardIds.isEmpty()) {
            return true;
        }
        List<UUID> distinct = cardIds.stream().distinct().toList();
        Integer count = jdbc.queryForObject("""
            SELECT count(*) FROM vocabulary_card
            WHERE learner_id = :learnerId AND id IN (:ids)
            """, Map.of("learnerId", learnerId, "ids", distinct), Integer.class);
        return count != null && count == distinct.size();
    }

    @Override
    public void removeItemFromCollection(UUID learnerId, UUID collectionId, UUID cardId) {
        int deleted = jdbc.update("""
            DELETE FROM vocabulary_collection_item ci
            USING vocabulary_collection col
            WHERE ci.collection_id = col.id
              AND col.id = :collectionId
              AND col.learner_id = :learnerId
              AND ci.card_id = :cardId
            """, Map.of("collectionId", collectionId, "learnerId", learnerId, "cardId", cardId));
        if (deleted != 1) {
            throw new ResourceNotFoundException("Vocabulary item not found in collection");
        }
    }

    @Override
    public int moveItems(UUID learnerId, UUID sourceCollectionId, UUID targetCollectionId, List<UUID> cardIds) {
        int moved = 0;
        for (UUID cardId : cardIds) {
            int deleted = jdbc.update("""
                DELETE FROM vocabulary_collection_item ci
                USING vocabulary_collection col, vocabulary_card c
                WHERE ci.collection_id = col.id
                  AND ci.card_id = c.id
                  AND col.id = :sourceId
                  AND col.learner_id = :learnerId
                  AND c.learner_id = :learnerId
                  AND ci.card_id = :cardId
                """, Map.of(
                "sourceId", sourceCollectionId,
                "learnerId", learnerId,
                "cardId", cardId
            ));
            if (deleted != 1) {
                throw new ResourceNotFoundException("Vocabulary item not found in source collection: " + cardId);
            }
            jdbc.update("""
                INSERT INTO vocabulary_collection_item (collection_id, card_id)
                VALUES (:targetId, :cardId)
                ON CONFLICT DO NOTHING
                """, Map.of("targetId", targetCollectionId, "cardId", cardId));
            moved++;
        }
        return moved;
    }

    private Map<UUID, List<UUID>> loadMemberships(List<UUID> cardIds) {
        if (cardIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<UUID>> result = new HashMap<>();
        jdbc.query("""
            SELECT card_id, collection_id
            FROM vocabulary_collection_item
            WHERE card_id IN (:cardIds)
            ORDER BY added_at ASC
            """, Map.of("cardIds", cardIds), rs -> {
            UUID cardId = rs.getObject("card_id", UUID.class);
            UUID colId = rs.getObject("collection_id", UUID.class);
            result.computeIfAbsent(cardId, k -> new ArrayList<>()).add(colId);
        });
        return result;
    }

    private static VocabularyCollection mapCollection(ResultSet rs, int rowNum) throws SQLException {
        return new VocabularyCollection(
            rs.getObject("id", UUID.class),
            rs.getObject("learner_id", UUID.class),
            rs.getString("name"),
            rs.getBoolean("is_default"),
            rs.getString("cover_preset"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant(),
            rs.getLong("item_count")
        );
    }

    private record RowWithAddedAt(VocabularyCard card, Instant addedAt) {}
}
