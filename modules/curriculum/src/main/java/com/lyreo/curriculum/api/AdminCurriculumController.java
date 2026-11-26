package com.lyreo.curriculum.api;

import com.lyreo.curriculum.application.CurriculumAdminService;
import com.lyreo.curriculum.domain.CurriculumItem;
import com.lyreo.curriculum.domain.CurriculumPath;
import com.lyreo.curriculum.domain.CurriculumPathDetail;
import com.lyreo.curriculum.domain.CurriculumPathDiagnostic;
import com.lyreo.curriculum.domain.CurriculumSection;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/curriculum/paths")
@PreAuthorize("hasRole('ADMIN')")
public class AdminCurriculumController {
    private final CurriculumAdminService adminService;

    public AdminCurriculumController(CurriculumAdminService adminService) {
        this.adminService = adminService;
    }

    @GetMapping
    public List<CurriculumPath> listPaths() {
        return adminService.listAllPaths();
    }

    @GetMapping("/{id}")
    public CurriculumPathDetail getPath(@PathVariable UUID id) {
        return adminService.getPath(id);
    }

    @GetMapping("/{id}/diagnostics")
    public CurriculumPathDiagnostic diagnosePath(@PathVariable UUID id) {
        return adminService.diagnosePath(id);
    }

    @PostMapping
    public ResponseEntity<CurriculumPathDetail> createPath(@Valid @RequestBody CreatePathRequest request) {
        CurriculumPathDetail created = adminService.createPath(toCommand(
            request.code(),
            request.name(),
            request.level(),
            request.description(),
            request.status(),
            request.coverAssetId(),
            request.accessMode(),
            request.requiredFeatureKey(),
            request.sections()
        ));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{id}")
    public CurriculumPathDetail updatePath(
        @PathVariable UUID id,
        @Valid @RequestBody UpdatePathRequest request
    ) {
        return adminService.updatePath(id, toCommand(
            request.code(),
            request.name(),
            request.level(),
            request.description(),
            request.status(),
            request.coverAssetId(),
            request.accessMode(),
            request.requiredFeatureKey(),
            request.sections()
        ));
    }

    @RequestMapping(value = "/{id}/status", method = {RequestMethod.PUT, RequestMethod.PATCH})
    public CurriculumPathDetail updateStatus(
        @PathVariable UUID id,
        @Valid @RequestBody StatusUpdateRequest request
    ) {
        return adminService.updateStatus(id, request.status());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePath(@PathVariable UUID id) {
        adminService.deletePath(id);
    }

    @PostMapping("/{id}/sections")
    public ResponseEntity<CurriculumSection> addSection(
        @PathVariable UUID id,
        @Valid @RequestBody AddSectionRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(adminService.addSection(id, request.title(), request.position()));
    }

    @PostMapping("/{id}/sections/{sectionId}/items")
    public ResponseEntity<CurriculumItem> addItem(
        @PathVariable UUID id,
        @PathVariable UUID sectionId,
        @Valid @RequestBody ItemRequest request
    ) {
        boolean required = request.required() == null || request.required();
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(adminService.addItem(id, sectionId, new CurriculumAdminService.ItemSpec(
                request.position(),
                request.contentType(),
                request.contentReferenceId(),
                required,
                request.unlockRule(),
                request.config()
            )));
    }

    private static CurriculumAdminService.SavePathCommand toCommand(
        String code,
        String name,
        String level,
        String description,
        String status,
        UUID coverAssetId,
        String accessMode,
        String requiredFeatureKey,
        List<SectionRequest> sections
    ) {
        List<CurriculumAdminService.SectionSpec> sectionSpecs = sections == null ? null : sections.stream()
            .map(section -> new CurriculumAdminService.SectionSpec(
                section.title(),
                section.position(),
                section.items() == null ? List.of() : section.items().stream()
                    .map(item -> new CurriculumAdminService.ItemSpec(
                        item.position(),
                        item.contentType(),
                        item.contentReferenceId(),
                        item.required() == null || item.required(),
                        item.unlockRule(),
                        item.config()
                    ))
                    .toList()
            ))
            .toList();
        return new CurriculumAdminService.SavePathCommand(
            code, name, level, description, status, coverAssetId, accessMode, requiredFeatureKey, sectionSpecs
        );
    }

    public record CreatePathRequest(
        @NotBlank String code,
        @NotBlank String name,
        String level,
        String description,
        String status,
        UUID coverAssetId,
        String accessMode,
        String requiredFeatureKey,
        @Valid List<SectionRequest> sections
    ) {
        public CreatePathRequest(
            String code,
            String name,
            String level,
            String description,
            String status,
            List<SectionRequest> sections
        ) {
            this(code, name, level, description, status, null, null, null, sections);
        }
    }

    public record UpdatePathRequest(
        String code,
        String name,
        String level,
        String description,
        String status,
        UUID coverAssetId,
        String accessMode,
        String requiredFeatureKey,
        @Valid List<SectionRequest> sections
    ) {
        public UpdatePathRequest(
            String code,
            String name,
            String level,
            String description,
            List<SectionRequest> sections
        ) {
            this(code, name, level, description, null, null, null, null, sections);
        }

        public UpdatePathRequest(
            String code,
            String name,
            String level,
            String description,
            String status,
            List<SectionRequest> sections
        ) {
            this(code, name, level, description, status, null, null, null, sections);
        }
    }

    public record StatusUpdateRequest(
        @NotBlank String status
    ) {}

    public record AddSectionRequest(
        @NotBlank String title,
        int position
    ) {}

    public record SectionRequest(
        @NotBlank String title,
        int position,
        @Valid List<ItemRequest> items
    ) {}

    public record ItemRequest(
        int position,
        @NotNull CurriculumItem.ContentType contentType,
        @NotNull UUID contentReferenceId,
        Boolean required,
        String unlockRule,
        Map<String, Object> config
    ) {
        public ItemRequest(
            int position,
            CurriculumItem.ContentType contentType,
            UUID contentReferenceId,
            Boolean required,
            String unlockRule
        ) {
            this(position, contentType, contentReferenceId, required, unlockRule, Map.of());
        }
    }
}
