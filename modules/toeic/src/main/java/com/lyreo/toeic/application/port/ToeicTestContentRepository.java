package com.lyreo.toeic.application.port;

import com.lyreo.toeic.domain.ToeicTestContent;
import java.util.Optional;
import java.util.UUID;

/** Read-only persistence port for active TOEIC test snapshots. */
public interface ToeicTestContentRepository {
    Optional<ToeicTestContent> findActiveTestContent(UUID catalogId, UUID testVersionId);
}
