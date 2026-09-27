package com.lyreo.entitlement.application.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lyreo.entitlement.api.FeatureEntitlementRequiredException;
import com.lyreo.entitlement.api.FeatureKey;
import com.lyreo.entitlement.application.port.FeatureRepository;
import com.lyreo.entitlement.application.port.UserEntitlementGrantRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DefaultEntitlementServiceTest {
    private static final UUID USER_ID = UUID.fromString("a6f6db04-8fd4-4e92-9329-57f4860ee7a4");
    private static final FeatureKey FEATURE = FeatureKey.of("grammar.advanced");
    private static final Instant NOW = Instant.parse("2026-09-27T00:00:00Z");

    private final FeatureRepository features = mock(FeatureRepository.class);
    private final UserEntitlementGrantRepository grants = mock(UserEntitlementGrantRepository.class);
    private final DefaultEntitlementService service = new DefaultEntitlementService(
        features,
        grants,
        Clock.fixed(NOW, ZoneOffset.UTC)
    );

    @Test
    void permitsAUserWithAnActiveGrant() {
        when(features.isActive(FEATURE)).thenReturn(true);
        when(grants.hasActiveGrant(USER_ID, FEATURE, NOW)).thenReturn(true);

        assertThat(service.hasFeature(USER_ID, FEATURE)).isTrue();
        verify(grants).hasActiveGrant(USER_ID, FEATURE, NOW);
    }

    @Test
    void treatsUnknownOrDeprecatedFeaturesAsUnavailable() {
        when(features.isActive(FEATURE)).thenReturn(false);

        assertThat(service.hasFeature(USER_ID, FEATURE)).isFalse();
        verifyNoInteractions(grants);
    }

    @Test
    void requireFeatureUsesTheApplicationsAccessDeniedConvention() {
        when(features.isActive(FEATURE)).thenReturn(true);
        when(grants.hasActiveGrant(USER_ID, FEATURE, NOW)).thenReturn(false);

        assertThatThrownBy(() -> service.requireFeature(USER_ID, FEATURE))
            .isInstanceOf(FeatureEntitlementRequiredException.class)
            .satisfies(error -> assertThat(((FeatureEntitlementRequiredException) error).featureKey()).isEqualTo(FEATURE));
    }
}
