package com.lyreo.platform.content;

import java.util.List;

public record CatalogPage<T>(List<T> items, long total, int page, int size) {}
