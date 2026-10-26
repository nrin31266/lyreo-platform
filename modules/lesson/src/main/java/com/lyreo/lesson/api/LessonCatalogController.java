package com.lyreo.lesson.api;

import com.lyreo.lesson.application.port.LessonCatalogPort;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
public class LessonCatalogController {
  private final LessonCatalogPort catalog;

  public LessonCatalogController(LessonCatalogPort catalog) {
    this.catalog = catalog;
  }

  @GetMapping("/lessons")
  public LessonCatalogPort.Page list(
      @RequestParam(defaultValue = "") String query,
      @RequestParam(defaultValue = "") String status,
      @RequestParam(required = false) UUID category,
      @RequestParam(defaultValue = "") String channel,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return catalog.list(query, status, category, channel, page, size);
  }

  @PutMapping("/lessons/{id}/metadata")
  public void update(@PathVariable UUID id, @RequestBody Metadata request) {
    catalog.updateMetadata(
        id,
        request.title(),
        request.description() == null ? "" : request.description(),
        request.imageUrl(),
        request.categoryIds());
  }

  @GetMapping("/lesson-categories")
  public List<LessonCatalogPort.Category> categories() {
    return catalog.categories();
  }

  @PostMapping("/lesson-categories")
  @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
  public LessonCatalogPort.Category create(@RequestBody CategoryName request) {
    return catalog.saveCategory(UUID.randomUUID(), request.name());
  }

  @PutMapping("/lesson-categories/{id}")
  public LessonCatalogPort.Category rename(
      @PathVariable UUID id, @RequestBody CategoryName request) {
    return catalog.saveCategory(id, request.name());
  }

  @DeleteMapping("/lesson-categories/{id}")
  @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
  public void delete(@PathVariable UUID id) {
    catalog.deleteCategory(id);
  }

  @GetMapping("/lesson-channels")
  public List<LessonCatalogPort.Channel> channels() {
    return catalog.channels();
  }

  @GetMapping("/lessons/{id}/metadata")
  public LessonCatalogPort.Item metadata(@PathVariable UUID id) {
    return catalog.get(id);
  }

  @DeleteMapping("/lessons/{id}")
  @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
  public void deleteDraft(@PathVariable UUID id) {
    catalog.deleteDraft(id);
  }

  @PutMapping("/lessons/{id}/visibility")
  public void visibility(@PathVariable UUID id, @RequestBody Visibility request) {
    catalog.visibility(id, request.visible());
  }

  public record Visibility(boolean visible) {}

  public record Metadata(
      String title, List<UUID> categoryIds, String description, String imageUrl) {
    public Metadata(String title, List<UUID> categoryIds) {
      this(title, categoryIds, "", null);
    }
  }

  public record CategoryName(String name) {}
}
