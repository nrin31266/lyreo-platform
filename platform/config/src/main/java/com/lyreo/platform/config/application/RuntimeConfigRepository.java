package com.lyreo.platform.config.application;

import com.lyreo.platform.config.domain.RuntimeConfigDocument;
import java.util.Optional;

public interface RuntimeConfigRepository {
    Optional<RuntimeConfigDocument> find(String ownerModule, String configKey);
    RuntimeConfigDocument save(RuntimeConfigDocument document);
}
