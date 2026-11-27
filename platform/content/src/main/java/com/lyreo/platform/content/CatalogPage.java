package com.lyreo.platform.content;

import java.util.List;

public record CatalogPage<T>(List<T> items, long total, int page, int size) {
    @com.fasterxml.jackson.annotation.JsonProperty("hasMore")
    public boolean hasMore() {
        return (long) (page + 1) * size < total;
    }
}
