package com.lyreo.curriculum.domain;

import java.util.UUID;

public record CurriculumSection(
    UUID id,
    UUID pathId,
    String title,
    int position
) {}
