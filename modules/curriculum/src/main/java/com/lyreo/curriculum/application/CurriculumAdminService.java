package com.lyreo.curriculum.application;

import com.lyreo.contracts.errors.RequestValidationException;
import com.lyreo.contracts.errors.ResourceNotFoundException;
import com.lyreo.contracts.errors.StateConflictException;
import com.lyreo.curriculum.application.port.CurriculumProgressRepository;
import com.lyreo.curriculum.domain.CurriculumItem;
import com.lyreo.curriculum.domain.CurriculumItem.ContentType;
import com.lyreo.curriculum.domain.CurriculumPath;
import com.lyreo.curriculum.domain.CurriculumPathDetail;
import com.lyreo.curriculum.domain.CurriculumSection;
import com.lyreo.entitlement.api.FeatureKey;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Admin authoring and lifecycle management for curriculum paths, sections, and items. */
public class CurriculumAdminService {
    private static final Set<String> VALID_STATUSES = Set.of(
        CurriculumPath.STATUS_DRAFT,
        CurriculumPath.STATUS_PUBLISHED,
        CurriculumPath.STATUS_ARCHIVED
    );

    private final CurriculumProgressRepository repository;
    private final CurriculumService curriculumService;
    private final ObjectMapper mapper;

    public CurriculumAdminService(
        CurriculumProgressRepository repository,
        CurriculumService curriculumService
    ) {
        this(repository, curriculumService, new ObjectMapper());
    }

    public CurriculumAdminService(
        CurriculumProgressRepository repository,
        CurriculumService curriculumService,
        ObjectMapper mapper
    ) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.curriculumService = Objects.requireNonNull(curriculumService, "curriculumService");
        this.mapper = mapper == null ? new ObjectMapper() : mapper;
    }

    @Transactional(readOnly = true)
    public List<CurriculumPath> listAllPaths() {
        return repository.listAllPaths();
    }

    @Transactional(readOnly = true)
    public CurriculumPathDetail getPath(UUID pathId) {
        CurriculumPath path = repository.findPathById(pathId)
            .orElseThrow(() -> new ResourceNotFoundException("Curriculum path not found"));
        return curriculumService.buildPathDetail(null, path);
    }

    @Transactional
    public CurriculumPathDetail createPath(SavePathCommand command) {
        String name = requireNonBlank(command.name(), "name");
        String code = resolveCreateCode(command.code(), name);
        String status = normalizeStatus(command.status(), CurriculumPath.STATUS_DRAFT);
        String accessMode = normalizeAccessMode(command.accessMode(), "PUBLIC");
        String featureKey = resolveFeatureKey(accessMode, command.requiredFeatureKey());

        UUID pathId = UUID.randomUUID();
        CurriculumPath created = repository.insertPath(
            pathId,
            code,
            name,
            trimToNull(command.level()),
            trimToNull(command.description()),
            status,
            command.coverAssetId(),
            accessMode,
            featureKey
        );

        if (command.sections() != null) {
            writeSectionsAndItems(pathId, command.sections());
        }

        return curriculumService.buildPathDetail(null, created);
    }

    @Transactional
    public CurriculumPathDetail updatePath(UUID pathId, SavePathCommand command) {
        CurriculumPath existing = repository.findPathById(pathId)
            .orElseThrow(() -> new ResourceNotFoundException("Curriculum path not found"));

        String code = command.code() != null && !command.code().isBlank()
            ? command.code().trim()
            : existing.code();
        String name = command.name() != null && !command.name().isBlank()
            ? command.name().trim()
            : existing.name();

        if (repository.existsByCode(code, pathId)) {
            throw new StateConflictException("Curriculum path code already exists: " + code);
        }

        String accessMode = normalizeAccessMode(command.accessMode(), existing.accessMode());
        String featureKey = resolveFeatureKey(
            accessMode,
            command.requiredFeatureKey() != null ? command.requiredFeatureKey() : existing.requiredFeatureKey()
        );

        CurriculumPath updated = repository.updatePath(
            pathId,
            code,
            name,
            trimToNull(command.level()),
            trimToNull(command.description()),
            command.coverAssetId(),
            accessMode,
            featureKey
        ).orElseThrow(() -> new ResourceNotFoundException("Curriculum path not found"));

        if (command.status() != null && !command.status().isBlank()) {
            String nextStatus = normalizeStatus(command.status(), existing.status());
            if (!nextStatus.equals(updated.status())) {
                updated = repository.updatePathStatus(pathId, nextStatus)
                    .orElseThrow(() -> new ResourceNotFoundException("Curriculum path not found"));
            }
        }

        if (command.sections() != null) {
            reconcileSectionsAndItems(pathId, command.sections());
        }

        return curriculumService.buildPathDetail(null, updated);
    }

    @Transactional
    public CurriculumPathDetail updateStatus(UUID pathId, String rawStatus) {
        String status = normalizeStatus(rawStatus, null);
        CurriculumPath updated = repository.updatePathStatus(pathId, status)
            .orElseThrow(() -> new ResourceNotFoundException("Curriculum path not found"));
        return curriculumService.buildPathDetail(null, updated);
    }

    @Transactional
    public void deletePath(UUID pathId) {
        if (!repository.deletePath(pathId)) {
            throw new ResourceNotFoundException("Curriculum path not found");
        }
    }

    @Transactional
    public CurriculumSection addSection(UUID pathId, String title, int position) {
        if (repository.findPathById(pathId).isEmpty()) {
            throw new ResourceNotFoundException("Curriculum path not found");
        }
        return repository.insertSection(
            UUID.randomUUID(),
            pathId,
            requireNonBlank(title, "title"),
            position
        );
    }

    @Transactional
    public CurriculumItem addItem(UUID pathId, UUID sectionId, ItemSpec itemSpec) {
        CurriculumSection section = repository.findSectionById(sectionId)
            .filter(s -> s.pathId().equals(pathId))
            .orElseThrow(() -> new ResourceNotFoundException("Curriculum section not found"));
        return repository.insertItem(
            UUID.randomUUID(),
            section.id(),
            itemSpec.position(),
            Objects.requireNonNull(itemSpec.contentType(), "contentType is required"),
            Objects.requireNonNull(itemSpec.contentReferenceId(), "contentReferenceId is required"),
            itemSpec.required(),
            normalizeUnlockRule(itemSpec.unlockRule(), itemSpec.required()),
            encodeConfig(itemSpec.config())
        );
    }

    private void reconcileSectionsAndItems(UUID pathId, List<SectionSpec> sections) {
        List<CurriculumSection> existingSections = repository.findSectionsByPathId(pathId);
        List<CurriculumItem> existingItems = repository.findItemsByPathId(pathId);
        Set<UUID> existingSectionIds = existingSections.stream()
            .map(CurriculumSection::id)
            .collect(java.util.stream.Collectors.toSet());
        Set<UUID> existingItemIds = existingItems.stream()
            .map(CurriculumItem::id)
            .collect(java.util.stream.Collectors.toSet());

        Set<UUID> retainedSectionIds = new java.util.HashSet<>();
        Set<UUID> retainedItemIds = new java.util.HashSet<>();
        for (SectionSpec sectionSpec : sections) {
            if (sectionSpec.id() != null && existingSectionIds.contains(sectionSpec.id())
                && !retainedSectionIds.add(sectionSpec.id())) {
                throw new RequestValidationException("Duplicate section id: " + sectionSpec.id());
            }
            if (sectionSpec.items() != null) {
                for (ItemSpec itemSpec : sectionSpec.items()) {
                    if (itemSpec.id() != null && existingItemIds.contains(itemSpec.id())
                        && !retainedItemIds.add(itemSpec.id())) {
                        throw new RequestValidationException("Duplicate item id: " + itemSpec.id());
                    }
                }
            }
        }

        List<UUID> removedItemIds = existingItemIds.stream()
            .filter(id -> !retainedItemIds.contains(id))
            .toList();
        List<UUID> removedSectionIds = existingSectionIds.stream()
            .filter(id -> !retainedSectionIds.contains(id))
            .toList();

        // Remove only deleted items/sections before offsetting remaining positions so surviving progress rows stay intact.
        repository.deleteItemsByIds(removedItemIds);
        repository.deleteSectionsByIds(removedSectionIds);

        if (!retainedItemIds.isEmpty()) {
            repository.offsetItemPositions(pathId, 100_000);
        }
        if (!retainedSectionIds.isEmpty()) {
            repository.offsetSectionPositions(pathId, 100_000);
        }

        int defaultSectionPos = 1;
        for (SectionSpec sectionSpec : sections) {
            int sectionPos = sectionSpec.position() > 0 ? sectionSpec.position() : defaultSectionPos;
            defaultSectionPos++;
            String title = requireNonBlank(sectionSpec.title(), "section title");
            UUID sectionId;
            if (sectionSpec.id() != null && existingSectionIds.contains(sectionSpec.id())) {
                sectionId = sectionSpec.id();
                repository.updateSection(sectionId, title, sectionPos);
            } else {
                sectionId = UUID.randomUUID();
                repository.insertSection(sectionId, pathId, title, sectionPos);
            }

            if (sectionSpec.items() != null) {
                int defaultItemPos = 1;
                for (ItemSpec itemSpec : sectionSpec.items()) {
                    int itemPos = itemSpec.position() > 0 ? itemSpec.position() : defaultItemPos;
                    defaultItemPos++;
                    ContentType contentType = Objects.requireNonNull(itemSpec.contentType(), "contentType is required");
                    UUID contentReferenceId = Objects.requireNonNull(
                        itemSpec.contentReferenceId(),
                        "contentReferenceId is required"
                    );
                    String unlockRule = normalizeUnlockRule(itemSpec.unlockRule(), itemSpec.required());
                    String configJson = encodeConfig(itemSpec.config());
                    if (itemSpec.id() != null && existingItemIds.contains(itemSpec.id())) {
                        repository.updateItem(
                            itemSpec.id(),
                            sectionId,
                            itemPos,
                            contentType,
                            contentReferenceId,
                            itemSpec.required(),
                            unlockRule,
                            configJson
                        );
                    } else {
                        repository.insertItem(
                            UUID.randomUUID(),
                            sectionId,
                            itemPos,
                            contentType,
                            contentReferenceId,
                            itemSpec.required(),
                            unlockRule,
                            configJson
                        );
                    }
                }
            }
        }
    }

    private void writeSectionsAndItems(UUID pathId, List<SectionSpec> sections) {
        int defaultSectionPos = 1;
        for (SectionSpec sectionSpec : sections) {
            int sectionPos = sectionSpec.position() > 0 ? sectionSpec.position() : defaultSectionPos;
            defaultSectionPos++;
            UUID sectionId = UUID.randomUUID();
            repository.insertSection(
                sectionId,
                pathId,
                requireNonBlank(sectionSpec.title(), "section title"),
                sectionPos
            );
            if (sectionSpec.items() != null) {
                int defaultItemPos = 1;
                for (ItemSpec itemSpec : sectionSpec.items()) {
                    int itemPos = itemSpec.position() > 0 ? itemSpec.position() : defaultItemPos;
                    defaultItemPos++;
                    repository.insertItem(
                        UUID.randomUUID(),
                        sectionId,
                        itemPos,
                        Objects.requireNonNull(itemSpec.contentType(), "contentType is required"),
                        Objects.requireNonNull(itemSpec.contentReferenceId(), "contentReferenceId is required"),
                        itemSpec.required(),
                        normalizeUnlockRule(itemSpec.unlockRule(), itemSpec.required()),
                        encodeConfig(itemSpec.config())
                    );
                }
            }
        }
    }

    private String encodeConfig(Map<String, Object> config) {
        if (config == null || config.isEmpty()) {
            return "{}";
        }
        try {
            return mapper.writeValueAsString(config);
        } catch (JacksonException e) {
            throw new RequestValidationException("Invalid item config JSON");
        }
    }

    private static String normalizeAccessMode(String raw, String fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback != null ? fallback : "PUBLIC";
        }
        String mode = raw.trim().toUpperCase(java.util.Locale.ROOT);
        if (!"PUBLIC".equals(mode) && !"FEATURE".equals(mode)) {
            throw new RequestValidationException("accessMode must be PUBLIC or FEATURE");
        }
        return mode;
    }

    private static String resolveFeatureKey(String accessMode, String rawFeatureKey) {
        if (!"FEATURE".equals(accessMode)) {
            return null;
        }
        if (rawFeatureKey == null || rawFeatureKey.isBlank()) {
            throw new RequestValidationException("requiredFeatureKey is required when accessMode is FEATURE");
        }
        return FeatureKey.of(rawFeatureKey.trim()).value();
    }

    private static String normalizeStatus(String rawStatus, String fallback) {
        if (rawStatus == null || rawStatus.isBlank()) {
            if (fallback != null) {
                return fallback;
            }
            throw new RequestValidationException("status is required");
        }
        String normalized = rawStatus.trim().toUpperCase(java.util.Locale.ROOT);
        if (!VALID_STATUSES.contains(normalized)) {
            throw new RequestValidationException("Invalid curriculum status: " + rawStatus);
        }
        return normalized;
    }

    private String resolveCreateCode(String rawCode, String name) {
        if (rawCode != null && !rawCode.isBlank()) {
            String trimmed = rawCode.trim();
            if (repository.existsByCode(trimmed, null)) {
                throw new StateConflictException("Curriculum path code already exists: " + trimmed);
            }
            return trimmed;
        }
        String base = generateCodeBase(name);
        if (!repository.existsByCode(base, null)) {
            return base;
        }
        for (int suffix = 2; suffix < 10_000; suffix++) {
            String tail = "_" + suffix;
            int maxBaseLen = Math.max(1, 80 - tail.length());
            String prefix = base.length() > maxBaseLen
                ? base.substring(0, maxBaseLen).replaceAll("_+$", "")
                : base;
            String candidate = (prefix.isEmpty() ? "PATH" : prefix) + tail;
            if (!repository.existsByCode(candidate, null)) {
                return candidate;
            }
        }
        return base + "_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private static String generateCodeBase(String name) {
        String replaced = name.replace('đ', 'd').replace('Đ', 'D');
        String normalized = Normalizer.normalize(replaced, Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "")
            .toUpperCase(Locale.ROOT)
            .replaceAll("[^A-Z0-9]+", "_")
            .replaceAll("^_+|_+$", "");
        if (normalized.isEmpty()) {
            return "PATH";
        }
        if (normalized.length() > 56) {
            normalized = normalized.substring(0, 56).replaceAll("_+$", "");
        }
        return normalized.isEmpty() ? "PATH" : normalized;
    }

    private static String normalizeUnlockRule(String raw, boolean required) {
        String normalized = raw == null || raw.isBlank()
            ? "PREVIOUS_REQUIRED"
            : raw.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("NONE", "PREVIOUS_REQUIRED", "OPEN", "FREE").contains(normalized)) {
            throw new RequestValidationException("Invalid unlock rule: " + raw);
        }
        if (!required && "NONE".equals(normalized)) {
            return "PREVIOUS_REQUIRED";
        }
        return normalized;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new RequestValidationException(field + " is required");
        }
        return value.trim();
    }

    public record SavePathCommand(
        String code,
        String name,
        String level,
        String description,
        String status,
        UUID coverAssetId,
        String accessMode,
        String requiredFeatureKey,
        List<SectionSpec> sections
    ) {
        public SavePathCommand(
            String code,
            String name,
            String level,
            String description,
            String status,
            List<SectionSpec> sections
        ) {
            this(code, name, level, description, status, null, null, null, sections);
        }
    }

    public record SectionSpec(
        UUID id,
        String title,
        int position,
        List<ItemSpec> items
    ) {
        public SectionSpec(
            String title,
            int position,
            List<ItemSpec> items
        ) {
            this(null, title, position, items);
        }
    }

    public record ItemSpec(
        UUID id,
        int position,
        ContentType contentType,
        UUID contentReferenceId,
        boolean required,
        String unlockRule,
        Map<String, Object> config
    ) {
        public ItemSpec(
            int position,
            ContentType contentType,
            UUID contentReferenceId,
            boolean required,
            String unlockRule,
            Map<String, Object> config
        ) {
            this(null, position, contentType, contentReferenceId, required, unlockRule, config);
        }

        public ItemSpec(
            int position,
            ContentType contentType,
            UUID contentReferenceId,
            boolean required,
            String unlockRule
        ) {
            this(null, position, contentType, contentReferenceId, required, unlockRule, Map.of());
        }
    }
}
