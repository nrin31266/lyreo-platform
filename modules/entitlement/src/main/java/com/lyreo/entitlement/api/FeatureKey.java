package com.lyreo.entitlement.api;

import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.modulith.NamedInterface;

/** A validated, stable feature capability key such as {@code grammar.advanced}. */
@NamedInterface(value = "api", propagate = false)
public record FeatureKey(String value) {
    private static final Pattern FORMAT = Pattern.compile("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*");

    public FeatureKey {
        Objects.requireNonNull(value, "value");
        if (value.length() > 64 || !FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("Feature key must be a lowercase capability key of at most 64 characters");
        }
    }

    public static FeatureKey of(String value) {
        return new FeatureKey(value);
    }
}
