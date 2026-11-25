package com.lyreo.vocabulary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lyreo.contracts.errors.IdempotencyConflictException;
import com.lyreo.contracts.errors.ResourceNotFoundException;
import com.lyreo.contracts.errors.StaleRevisionException;
import com.lyreo.contracts.errors.StateConflictException;
import com.lyreo.contracts.vocabulary.VocabularyReviewCompletedEvent;
import com.lyreo.contracts.vocabulary.VocabularyUnitCompletedEvent;
import com.lyreo.entitlement.api.QuotaExceededException;
import com.lyreo.platform.content.AssetStore;
import com.lyreo.platform.content.DocumentStore;
import com.lyreo.platform.storage.ObjectStoragePort;
import com.lyreo.vocabulary.application.VocabularyCollectionService;
import com.lyreo.vocabulary.application.VocabularyCommandService;
import com.lyreo.vocabulary.application.VocabularyCommandService.SaveItemCommand;
import com.lyreo.vocabulary.application.VocabularyCommandService.SavedItemResult;
import com.lyreo.vocabulary.application.port.SpacedRepetitionScheduler;
import com.lyreo.vocabulary.domain.VocabularyCollection;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

class SavedVocabularyCollectionIT {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine");
    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static NamedParameterJdbcTemplate namedJdbc;
    private static PlatformTransactionManager txManager;
    private static ObjectMapper mapper;

    private AnnotationConfigApplicationContext context;
    private List<VocabularyReviewCompletedEvent> reviewEvents;
    private List<VocabularyUnitCompletedEvent> unitEvents;
    private VocabularyCommandService commandService;
    private VocabularyCollectionService collectionService;

    @Configuration
    @EnableTransactionManagement
    static class TxConfig {}

    @BeforeAll
    static void startDb() {
        POSTGRES.start();
        dataSource = new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()
        );
        Path migrations = Path.of("../../apps/core-service/src/main/resources/db/migration")
            .toAbsolutePath().normalize();
        Flyway.configure().dataSource(dataSource).locations("filesystem:" + migrations).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        namedJdbc = new NamedParameterJdbcTemplate(dataSource);
        txManager = new JdbcTransactionManager(dataSource);
        mapper = new ObjectMapper();
    }

    @BeforeEach
    void wireServices() {
        reviewEvents = new CopyOnWriteArrayList<>();
        unitEvents = new CopyOnWriteArrayList<>();

        context = new AnnotationConfigApplicationContext();
        context.register(TxConfig.class);
        context.registerBean(DataSource.class, () -> dataSource);
        context.registerBean(JdbcTemplate.class, () -> jdbc);
        context.registerBean(NamedParameterJdbcTemplate.class, () -> namedJdbc);
        context.registerBean(PlatformTransactionManager.class, () -> txManager);
        context.registerBean(ObjectMapper.class, () -> mapper);
        context.registerBean(ObjectStoragePort.class, NoopStorage::new);
        context.registerBean(DocumentStore.class);
        context.registerBean(AssetStore.class);
        context.addApplicationListener(event -> {
            if (event instanceof PayloadApplicationEvent<?> payload) {
                if (payload.getPayload() instanceof VocabularyReviewCompletedEvent r) {
                    reviewEvents.add(r);
                } else if (payload.getPayload() instanceof VocabularyUnitCompletedEvent u) {
                    unitEvents.add(u);
                }
            }
        });
        context.scan(
            "com.lyreo.platform.config.infrastructure",
            "com.lyreo.entitlement.infrastructure",
            "com.lyreo.lexicon.infrastructure",
            "com.lyreo.vocabulary.infrastructure"
        );
        context.refresh();

        commandService = context.getBean(VocabularyCommandService.class);
        collectionService = context.getBean(VocabularyCollectionService.class);
    }

    @AfterEach
    void closeContext() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void supportsTwoSensesConcurrentDedupSnapshotRetentionAtomicMoveDeleteAndReviewIdempotency() throws Exception {
        UUID learnerA = UUID.randomUUID();
        UUID learnerB = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id, keycloak_subject) VALUES (?, ?)", learnerA, "col-a-" + learnerA);
        jdbc.update("INSERT INTO app_user(id, keycloak_subject) VALUES (?, ?)", learnerB, "col-b-" + learnerB);

        UUID releaseLex = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO dataset_release(id, domain, package_version, schema_version, checksum_sha256, manifest_json)
            VALUES (?, 'lexicon', ?, '1.0', ?, '{}'::jsonb)
            """, releaseLex, "lex-" + releaseLex.toString().substring(0, 8), "b".repeat(64));
        UUID bankHeadwordId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO lexicon_headword(id, language, identity_form)
            VALUES (?, 'en', 'bank')
            """, bankHeadwordId);
        jdbc.update("""
            INSERT INTO lexicon_entry(id, release_id, headword_id, package_entry_id, display_form, lookup_form, entry_type)
            VALUES (?, ?, ?, ?, 'bank', 'bank', 'WORD')
            """, UUID.randomUUID(), releaseLex, bankHeadwordId, UUID.randomUUID());
        jdbc.update("""
            INSERT INTO dataset_import_run(id, release_id, run_type, status, completed_at)
            VALUES (?, ?, 'APPLY', 'SUCCEEDED', now())
            """, UUID.randomUUID(), releaseLex);
        jdbc.update("""
            INSERT INTO dataset_active_release(domain, release_id, activated_by)
            VALUES ('lexicon', ?, 'test')
            ON CONFLICT (domain) DO UPDATE SET release_id = excluded.release_id
            """, releaseLex);

        UUID releaseVoc = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO dataset_release(id, domain, package_version, schema_version, checksum_sha256, manifest_json)
            VALUES (?, 'vocabulary', ?, '1.0', ?, '{}'::jsonb)
            """, releaseVoc, "voc-" + releaseVoc.toString().substring(0, 8), "c".repeat(64));
        UUID packId = UUID.randomUUID();
        UUID unitId = UUID.randomUUID();
        UUID entryId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO vocabulary_pack(id, release_id, source_topic, position, title_en, title_vi)
            VALUES (?, ?, 'finance', 1, 'Finance', 'Tài chính')
            """, packId, releaseVoc);
        jdbc.update("""
            INSERT INTO vocabulary_unit(id, pack_id, position, level, title)
            VALUES (?, ?, 1, 'B1', 'Money')
            """, unitId, packId);
        String entryContent = mapper.writeValueAsString(Map.of(
            "ipaUk", "/bæŋk/",
            "ipaUs", "/bæŋk/",
            "senses", List.of(
                Map.of("pos", "n.", "definitionEn", "a financial institution", "meaningVi", "ngân hàng"),
                Map.of("pos", "n.", "definitionEn", "land alongside a river", "meaningVi", "bờ sông")
            ),
            "examples", List.of("I went to the bank.", "We sat on the river bank."),
            "collocations", List.of(),
            "family", List.of(),
            "synonyms", List.of(),
            "antonyms", List.of()
        ));
        jdbc.update("""
            INSERT INTO vocabulary_unit_entry(id, unit_id, position, source_id, term, content, revision)
            VALUES (?, ?, 1, ?, 'bank', CAST(? AS jsonb), 1)
            """, entryId, unitId, "src-" + entryId, entryContent);

        // 1. Save 2 senses of the SAME word -> 2 distinct cards, both linked to headword "bank"
        SavedItemResult sense0 = commandService.saveItem(learnerA, new SaveItemCommand(
            null, "bank", null, null, null, null, null, null,
            "VOCABULARY_ENTRY", entryId, entryId, 0, null, null, null, null, null
        ), null);
        SavedItemResult sense1 = commandService.saveItem(learnerA, new SaveItemCommand(
            null, "bank", null, null, null, null, null, null,
            "VOCABULARY_ENTRY", entryId, entryId, 1, null, null, null, null, null
        ), null);

        assertThat(sense0.id()).isNotEqualTo(sense1.id());
        assertThat(sense0.headwordId()).isEqualTo(bankHeadwordId);
        assertThat(sense1.headwordId()).isEqualTo(bankHeadwordId);
        assertThat(sense0.meaningVi()).isEqualTo("ngân hàng");
        assertThat(sense1.meaningVi()).isEqualTo("bờ sông");

        // 2. Concurrent duplicate save of sense0 -> deduplicates to exact same card
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<SavedItemResult> saveTask = () -> {
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return commandService.saveItem(learnerA, new SaveItemCommand(
                    null, "bank", null, null, null, null, null, null,
                    "VOCABULARY_ENTRY", entryId, entryId, 0, null, null, null, null, null
                ), null);
            };
            Future<SavedItemResult> f1 = executor.submit(saveTask);
            Future<SavedItemResult> f2 = executor.submit(saveTask);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            SavedItemResult r1 = f1.get(10, TimeUnit.SECONDS);
            SavedItemResult r2 = f2.get(10, TimeUnit.SECONDS);
            assertThat(r1.id()).isEqualTo(sense0.id());
            assertThat(r2.id()).isEqualTo(sense0.id());
        }

        // 3. Source entry revision change + hidden retention
        jdbc.update("""
            UPDATE vocabulary_unit_entry
            SET hidden = true, revision = 99,
                content = jsonb_set(content, '{senses,0,meaningVi}', '"đã đổi"'::jsonb)
            WHERE id = ?
            """, entryId);
        jdbc.update("UPDATE vocabulary_pack SET hidden = true WHERE id = ?", packId);

        List<VocabularyCollection> cols = collectionService.listCollections(learnerA);
        VocabularyCollection defaultCol = cols.stream().filter(VocabularyCollection::isDefault).findFirst().orElseThrow();
        assertThat(defaultCol.name()).isEqualTo("Đã lưu");
        assertThat(defaultCol.itemCount()).isEqualTo(2);

        var defaultItems = collectionService.listItems(learnerA, defaultCol.id(), "", 0, 20);
        assertThat(defaultItems.items()).hasSize(2);
        assertThat(defaultItems.items())
            .extracting(i -> i.card().meaningVi())
            .containsExactlyInAnyOrder("ngân hàng", "bờ sông");

        // 4. Collection CRUD, duplicate name conflict, cannot delete default, cross-learner 404
        VocabularyCollection colWork = collectionService.createCollection(learnerA, "Business", "GREEN_LEAF");
        VocabularyCollection colTravel = collectionService.createCollection(learnerA, "Travel", "CORAL");
        assertThatThrownBy(() -> collectionService.createCollection(learnerA, "  business ", null))
            .isInstanceOf(StateConflictException.class);
        assertThatThrownBy(() -> collectionService.deleteCollection(learnerA, defaultCol.id()))
            .isInstanceOf(StateConflictException.class);
        assertThatThrownBy(() -> collectionService.listItems(learnerB, colWork.id(), "", 0, 20))
            .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> collectionService.deleteCollection(learnerB, colWork.id()))
            .isInstanceOf(ResourceNotFoundException.class);

        // 5. Atomic move rollback when one cardId is invalid
        collectionService.addItems(learnerA, colWork.id(), List.of(sense0.id(), sense1.id()));
        UUID nonExistentCard = UUID.randomUUID();
        assertThatThrownBy(() -> collectionService.moveItems(
            learnerA, colWork.id(), colTravel.id(), List.of(sense0.id(), nonExistentCard)
        )).isInstanceOf(ResourceNotFoundException.class);
        assertThat(collectionService.listItems(learnerA, colWork.id(), "", 0, 20).total()).isEqualTo(2);
        assertThat(collectionService.listItems(learnerA, colTravel.id(), "", 0, 20).total()).isZero();

        // Remove sense0 from defaultCol so sense0 is ONLY in colWork, while sense1 is in defaultCol + colWork + colTravel
        collectionService.removeItem(learnerA, defaultCol.id(), sense0.id());
        collectionService.addItems(learnerA, colTravel.id(), List.of(sense1.id()));

        // Delete colWork -> orphaned sense0 moves atomically to "Đã lưu"; sense1 stays preserved in colTravel & defaultCol
        collectionService.deleteCollection(learnerA, colWork.id());
        var afterDeleteDefault = collectionService.listItems(learnerA, defaultCol.id(), "", 0, 20);
        assertThat(afterDeleteDefault.items()).extracting(i -> i.card().id())
            .containsExactlyInAnyOrder(sense0.id(), sense1.id());
        assertThat(collectionService.listItems(learnerA, colTravel.id(), "", 0, 20).items())
            .extracting(i -> i.card().id())
            .containsExactly(sense1.id());

        assertThat(reviewEvents).isEmpty();
        assertThat(unitEvents).isEmpty();

        // 6. Review idempotency, conflict, cross-learner 404, and event emission
        assertThatThrownBy(() -> commandService.review(learnerB, sense0.id(), SpacedRepetitionScheduler.Rating.GOOD, 0, "r-cross"))
            .isInstanceOf(ResourceNotFoundException.class);

        var rev1 = commandService.review(learnerA, sense0.id(), SpacedRepetitionScheduler.Rating.GOOD, 0, "rev-key-1");
        assertThat(reviewEvents).hasSize(1);
        UUID firstReviewId = reviewEvents.getFirst().reviewId();
        assertThat(firstReviewId).isNotNull();
        assertThat(reviewEvents.getFirst().cardId()).isEqualTo(sense0.id());

        var rev1Replay = commandService.review(learnerA, sense0.id(), SpacedRepetitionScheduler.Rating.GOOD, 0, "rev-key-1");
        assertThat(rev1Replay).isEqualTo(rev1);
        assertThat(reviewEvents).hasSize(1);

        assertThatThrownBy(() -> commandService.review(learnerA, sense0.id(), SpacedRepetitionScheduler.Rating.EASY, 0, "rev-key-1"))
            .isInstanceOf(IdempotencyConflictException.class);

        assertThatThrownBy(() -> commandService.review(learnerA, sense0.id(), SpacedRepetitionScheduler.Rating.GOOD, 0, "rev-key-2"))
            .isInstanceOf(StaleRevisionException.class);

        // 7. Pagination & search (> 20 items) + FREE capacity limit 100
        for (int i = 0; i < 23; i++) {
            commandService.saveItem(learnerA, new SaveItemCommand(
                null, "word-" + i, "n.", "nghĩa " + i, "def " + i, null, null, null,
                "CUSTOM", null, null, null, null, null, null, null, List.of(colTravel.id())
            ), null);
        }
        var page0 = collectionService.listItems(learnerA, colTravel.id(), "", 0, 20);
        var page1 = collectionService.listItems(learnerA, colTravel.id(), "", 1, 20);
        assertThat(page0.items()).hasSize(20);
        assertThat(page0.hasMore()).isTrue();
        assertThat(page1.items()).hasSize(4);
        assertThat(page1.hasMore()).isFalse();

        var search = collectionService.listItems(learnerA, colTravel.id(), "nghĩa 22", 0, 20);
        assertThat(search.total()).isEqualTo(1);
        assertThat(search.items().getFirst().card().term()).isEqualTo("word-22");

        // Fill learnerB to 100 cards (FREE limit) and verify 101st fails with QuotaExceededException
        for (int i = 0; i < 100; i++) {
            commandService.saveItem(learnerB, new SaveItemCommand(
                null, "cap-" + i, "n.", "nghĩa " + i, null, null, null, null,
                null, null, null, null, null, null, null, null, null
            ), null);
        }
        assertThatThrownBy(() -> commandService.saveItem(learnerB, new SaveItemCommand(
            null, "cap-100", "n.", "nghĩa 100", null, null, null, null,
            null, null, null, null, null, null, null, null, null
        ), null)).isInstanceOf(QuotaExceededException.class);
    }

    private static final class NoopStorage implements ObjectStoragePort {
        @Override public StoredObject put(String key, String contentType, byte[] bytes) {
            return new StoredObject(key, bytes.length, "etag");
        }
        @Override public InputStream get(String key) { return new ByteArrayInputStream(new byte[0]); }
        @Override public boolean exists(String key) { return false; }
        @Override public void delete(String key) {}
        @Override public java.net.URI createDownloadUrl(String key, java.time.Duration ttl) {
            return java.net.URI.create("https://example.test/" + key);
        }
    }
}
