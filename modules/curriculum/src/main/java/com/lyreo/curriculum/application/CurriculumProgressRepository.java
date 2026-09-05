package com.lyreo.curriculum.application;

import com.lyreo.curriculum.domain.CurriculumItem.ContentType;
import java.util.List;
import java.util.UUID;

public interface CurriculumProgressRepository {
    List<UUID> itemIdsReferencing(ContentType type, UUID contentReferenceId);
    void complete(UUID learnerId, UUID itemId);
}
