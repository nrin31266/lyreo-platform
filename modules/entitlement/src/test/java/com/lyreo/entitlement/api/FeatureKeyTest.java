package com.lyreo.entitlement.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class FeatureKeyTest {
    @Test
    void acceptsStableLowercaseCapabilityKeys() {
        assertThat(FeatureKey.of("grammar.advanced").value()).isEqualTo("grammar.advanced");
        assertThat(FeatureKey.of("lexicon.full-access").value()).isEqualTo("lexicon.full-access");
    }

    @Test
    void rejectsBlankMalformedAndOverlongKeys() {
        assertThatThrownBy(() -> FeatureKey.of(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FeatureKey.of("Grammar.Advanced")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FeatureKey.of("grammar..advanced")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FeatureKey.of("a".repeat(65))).isInstanceOf(IllegalArgumentException.class);
        assertThatNullPointerException().isThrownBy(() -> FeatureKey.of(null));
    }
}
