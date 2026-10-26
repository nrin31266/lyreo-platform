package com.lyreo.lesson.application.port;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface LessonCatalogPort {
  Page list(String query, String status, UUID category, String channel, int page, int size);

  List<Category> categories();

  Category saveCategory(UUID id, String name);

  void deleteCategory(UUID id);

  List<Channel> channels();

  void update(UUID lesson, String title, List<UUID> categories);

  Item get(UUID lesson);

  void updateMetadata(
      UUID lesson, String title, String description, String imageUrl, List<UUID> categories);

  void deleteDraft(UUID lesson);

  void visibility(UUID lesson, boolean visible);

  record Category(UUID id, String name, long lessonCount) {}

  record Channel(
      String id,
      String title,
      long lessonCount,
      String avatarUrl,
      Instant syncedAt,
      String syncError,
      UUID syncJobId,
      String syncStatus) {}

  record Item(
      UUID id,
      String title,
      String sourceType,
      String status,
      Long startMs,
      Long endMs,
      String channelId,
      String channelTitle,
      Instant updatedAt,
      boolean authoring,
      List<Category> categories,
      String description,
      String imageUrl,
      boolean visible) {}

  record Page(List<Item> items, long total, int page, int size) {}
}
