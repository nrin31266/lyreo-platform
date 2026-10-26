package com.lyreo.lesson.infrastructure.persistence;

import com.lyreo.contracts.errors.RequestValidationException;
import com.lyreo.contracts.errors.ResourceNotFoundException;
import com.lyreo.contracts.errors.StateConflictException;
import com.lyreo.lesson.application.port.LessonCatalogPort;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcLessonCatalog implements LessonCatalogPort {
  private final NamedParameterJdbcTemplate jdbc;
  private final com.lyreo.platform.jobs.application.BackgroundJobService jobs;

  public JdbcLessonCatalog(
      NamedParameterJdbcTemplate jdbc,
      com.lyreo.platform.jobs.application.BackgroundJobService jobs) {
    this.jdbc = jdbc;
    this.jobs = jobs;
  }

  @Override
  public Page list(String query, String status, UUID category, String channel, int page, int size) {
    return query(query, status, category, channel, page, size, null);
  }

  private Page query(
      String query,
      String status,
      UUID category,
      String channel,
      int page,
      int size,
      UUID lessonId) {
    if (page < 0 || size < 1 || size > 100 || query.length() > 200)
      throw new RequestValidationException("Invalid catalog query");
    var args =
        new MapSqlParameterSource()
            .addValue(
                "query",
                "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%")
            .addValue("status", status)
            .addValue("category", category)
            .addValue("channel", channel)
            .addValue("limit", size)
            .addValue("offset", (long) page * size);
    String from =
        """
         FROM lesson l JOIN lesson_metadata m ON m.lesson_id=l.id AND m.deleted_at IS NULL LEFT JOIN lesson_authoring a ON a.lesson_id=l.id
         LEFT JOIN lesson_youtube_source s ON s.video_id=a.video_id
         LEFT JOIN lesson_youtube_channel ch ON ch.channel_id=s.channel_id
         WHERE m.title ILIKE :query
        """;
    if (lessonId != null) {
      from += " AND l.id=:lessonId";
      args.addValue("lessonId", lessonId);
    }
    if (!status.isEmpty()) from += " AND l.status=:status";
    if (category != null)
      from +=
          " AND EXISTS(SELECT 1 FROM lesson_category_membership m WHERE m.lesson_id=l.id AND"
              + " m.category_id=:category)";
    if (!channel.isEmpty()) from += " AND s.channel_id=:channel";
    long total = jdbc.queryForObject("SELECT count(*)" + from, args, Long.class);
    var items =
        jdbc.query(
            "SELECT l.*,m.title AS"
                + " display_title,m.description,m.image_url,m.visible,GREATEST(m.updated_at,l.updated_at)"
                + " AS metadata_updated_at,s.thumbnail_url,a.source_start_ms,a.source_end_ms,a.lesson_id,s.channel_id,COALESCE(ch.title,s.channel_title)"
                + " AS channel_title"
                + from
                + " ORDER BY GREATEST(m.updated_at,l.updated_at) DESC,l.id LIMIT :limit OFFSET"
                + " :offset",
            args,
            (rs, n) ->
                new Item(
                    rs.getObject("id", UUID.class),
                    rs.getString("display_title"),
                    rs.getString("source_type"),
                    rs.getString("status"),
                    rs.getObject("source_start_ms", Long.class),
                    rs.getObject("source_end_ms", Long.class),
                    rs.getString("channel_id"),
                    rs.getString("channel_title"),
                    rs.getTimestamp("metadata_updated_at").toInstant(),
                    rs.getObject("lesson_id") != null,
                    List.of(),
                    rs.getString("description"),
                    rs.getString("image_url") == null
                        ? rs.getString("thumbnail_url")
                        : rs.getString("image_url"),
                    rs.getBoolean("visible")));
    if (items.isEmpty()) return new Page(items, total, page, size);
    var memberships =
        jdbc.query(
            """
            SELECT m.lesson_id,c.id,c.name FROM lesson_category_membership m JOIN lesson_category c ON c.id=m.category_id
            WHERE m.lesson_id IN (:ids) ORDER BY lower(c.name)
            """,
            new MapSqlParameterSource("ids", items.stream().map(Item::id).toList()),
            (rs, n) ->
                new Membership(
                    rs.getObject(1, UUID.class),
                    new Category(rs.getObject(2, UUID.class), rs.getString(3), 0)));
    var byLesson =
        memberships.stream()
            .collect(
                java.util.stream.Collectors.groupingBy(
                    Membership::lesson,
                    java.util.stream.Collectors.mapping(
                        Membership::category, java.util.stream.Collectors.toList())));
    return new Page(
        items.stream()
            .map(
                item ->
                    new Item(
                        item.id(),
                        item.title(),
                        item.sourceType(),
                        item.status(),
                        item.startMs(),
                        item.endMs(),
                        item.channelId(),
                        item.channelTitle(),
                        item.updatedAt(),
                        item.authoring(),
                        byLesson.getOrDefault(item.id(), List.of()),
                        item.description(),
                        item.imageUrl(),
                        item.visible()))
            .toList(),
        total,
        page,
        size);
  }

  private record Membership(UUID lesson, Category category) {}

  @Override
  public List<Category> categories() {
    return jdbc.query(
        "SELECT c.id,c.name,count(m.lesson_id) AS count FROM lesson_category c LEFT JOIN"
            + " lesson_category_membership m ON m.category_id=c.id GROUP BY c.id ORDER BY"
            + " lower(c.name)",
        new MapSqlParameterSource(),
        (rs, n) -> new Category(rs.getObject(1, UUID.class), rs.getString(2), rs.getLong(3)));
  }

  @Override
  @Transactional
  public Category saveCategory(UUID id, String name) {
    if (name == null || name.isBlank() || name.strip().length() > 100)
      throw new RequestValidationException("Category name must contain 1–100 characters");
    try {
      var args = new MapSqlParameterSource("id", id).addValue("name", name.strip());
      if (jdbc.update("UPDATE lesson_category SET name=:name WHERE id=:id", args) == 0)
        jdbc.update("INSERT INTO lesson_category(id,name) VALUES(:id,:name)", args);
      return new Category(id, name.strip(), 0);
    } catch (DuplicateKeyException duplicate) {
      throw new StateConflictException("A category with this name already exists");
    }
  }

  @Override
  @Transactional
  public void deleteCategory(UUID id) {
    if (jdbc.query(
            "SELECT id FROM lesson_category WHERE id=:id FOR UPDATE",
            java.util.Map.of("id", id),
            (rs, n) -> rs.getObject(1))
        .isEmpty()) throw new ResourceNotFoundException("Category not found");
    if (jdbc.queryForObject(
            "SELECT count(*) FROM lesson_category_membership WHERE category_id=:id",
            java.util.Map.of("id", id),
            Long.class)
        > 0)
      throw new StateConflictException("Remove category assignments before deleting this category");
    jdbc.update("DELETE FROM lesson_category WHERE id=:id", java.util.Map.of("id", id));
  }

  @Override
  public List<Channel> channels() {
    return jdbc.query(
        """
        SELECT c.*,(SELECT count(*) FROM lesson_authoring a
          JOIN lesson_youtube_source s ON s.video_id=a.video_id
          JOIN lesson_metadata m ON m.lesson_id=a.lesson_id AND m.deleted_at IS NULL
          WHERE s.channel_id=c.channel_id) AS lesson_count
        FROM lesson_youtube_channel c ORDER BY lower(c.title)
        """,
        new MapSqlParameterSource(),
        (rs, n) -> {
          UUID jobId = rs.getObject("sync_job_id", UUID.class);
          var job = jobId == null ? null : jobs.findById(jobId).orElse(null);
          return new Channel(
              rs.getString("channel_id"),
              rs.getString("title"),
              rs.getLong("lesson_count"),
              rs.getString("avatar_url"),
              rs.getTimestamp("synced_at") == null
                  ? null
                  : rs.getTimestamp("synced_at").toInstant(),
              rs.getString("sync_error") == null && job != null
                  ? job.errorMessage()
                  : rs.getString("sync_error"),
              rs.getObject("sync_job_id", UUID.class),
              job == null ? null : job.status().name());
        });
  }

  @Override
  public Item get(UUID lesson) {
    return query("", "", null, "", 0, 1, lesson).items().stream()
        .findFirst()
        .orElseThrow(() -> new ResourceNotFoundException("Lesson not found"));
  }

  @Override
  @Transactional
  public void update(UUID lesson, String title, List<UUID> categories) {
    var current = get(lesson);
    updateMetadata(lesson, title, current.description(), current.imageUrl(), categories);
  }

  private String lock(UUID lesson) {
    var rows =
        jdbc.query(
            """
            SELECT l.status FROM lesson l JOIN lesson_metadata m ON m.lesson_id=l.id
            WHERE l.id=:id AND m.deleted_at IS NULL FOR UPDATE OF l,m
            """,
            java.util.Map.of("id", lesson),
            (rs, n) -> rs.getString(1));
    if (rows.isEmpty()) throw new ResourceNotFoundException("Lesson not found");
    return rows.getFirst();
  }

  @Override
  @Transactional
  public void updateMetadata(
      UUID lesson, String title, String description, String imageUrl, List<UUID> categories) {
    lock(lesson);
    if (categories == null
        || categories.size() > 100
        || categories.stream().anyMatch(java.util.Objects::isNull)
        || new java.util.HashSet<>(categories).size() != categories.size())
      throw new RequestValidationException("Provide up to 100 distinct category IDs");
    if (title != null && (title.isBlank() || title.strip().length() > 500))
      throw new RequestValidationException("Title must contain 1–500 characters");
    if (description == null || description.length() > 5000)
      throw new RequestValidationException("Description exceeds 5000 characters");
    if (imageUrl != null && !imageUrl.isBlank()) {
      try {
        var uri = java.net.URI.create(imageUrl);
        if (!java.util.Set.of("http", "https").contains(uri.getScheme())
            || uri.getHost() == null
            || uri.getUserInfo() != null
            || imageUrl.length() > 2048) throw new IllegalArgumentException();
      } catch (IllegalArgumentException invalid) {
        throw new RequestValidationException("Provide an HTTP(S) image URL");
      }
    }
    var args = new MapSqlParameterSource("id", lesson);
    if (!categories.isEmpty()) {
      args.addValue("categories", categories);
      var found =
          jdbc.query(
              "SELECT id FROM lesson_category WHERE id IN (:categories) FOR KEY SHARE",
              args,
              (rs, n) -> rs.getObject(1, UUID.class));
      if (found.size() != categories.size())
        throw new RequestValidationException("Unknown category");
    }
    jdbc.update(
        "UPDATE lesson_metadata SET"
            + " title=COALESCE(:title,title),description=:description,image_url=:image,updated_at=now()"
            + " WHERE lesson_id=:id",
        args.addValue("title", title == null ? null : title.strip())
            .addValue("description", description)
            .addValue("image", imageUrl == null || imageUrl.isBlank() ? null : imageUrl));
    jdbc.update("DELETE FROM lesson_category_membership WHERE lesson_id=:id", args);
    for (UUID category : categories)
      jdbc.update(
          "INSERT INTO lesson_category_membership(lesson_id,category_id) VALUES(:id,:category)",
          args.addValue("category", category));
  }

  @Override
  @Transactional
  public void deleteDraft(UUID lesson) {
    if ("PUBLISHED".equals(lock(lesson)))
      throw new StateConflictException("Unlist published lessons instead of deleting them");
    var linked =
        jdbc.query(
            """
            SELECT job_id FROM lesson_authoring_job WHERE lesson_id=:id
            UNION SELECT job_id FROM lesson_build_job WHERE lesson_id=:id
            """,
            java.util.Map.of("id", lesson),
            (rs, n) -> rs.getObject(1, UUID.class));
    if (linked.stream()
        .map(jobs::findById)
        .flatMap(java.util.Optional::stream)
        .anyMatch(
            job ->
                java.util.Set.of("QUEUED", "RUNNING", "RETRY_WAIT", "CANCEL_REQUESTED")
                    .contains(job.status().name())))
      throw new StateConflictException("Cancel active jobs before deleting this draft");
    jdbc.update(
        "DELETE FROM lesson_category_membership WHERE lesson_id=:id",
        java.util.Map.of("id", lesson));
    jdbc.update(
        "UPDATE lesson_metadata SET deleted_at=now(),updated_at=now() WHERE lesson_id=:id",
        java.util.Map.of("id", lesson));
  }

  @Override
  @Transactional
  public void visibility(UUID lesson, boolean visible) {
    if (!"PUBLISHED".equals(lock(lesson)))
      throw new StateConflictException("Only published lessons have visibility controls");
    jdbc.update(
        "UPDATE lesson_metadata SET visible=:visible,updated_at=now() WHERE lesson_id=:id",
        java.util.Map.of("id", lesson, "visible", visible));
  }
}
