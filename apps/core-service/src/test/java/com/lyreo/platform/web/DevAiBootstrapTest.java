package com.lyreo.platform.web;

import com.lyreo.ai.application.admin.AiAdminService;
import com.lyreo.ai.application.admin.ProviderSummary;
import com.lyreo.ai.infrastructure.DevAiBootstrap;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class DevAiBootstrapTest {
    @Test void preservesConfiguredProvidersIncludingDisabledMock() {
        var admin = mock(AiAdminService.class);
        var mockProvider = mock(ProviderSummary.class);
        when(mockProvider.code()).thenReturn("DEV_MOCK");
        when(admin.providers()).thenReturn(List.of(mockProvider));
        new DevAiBootstrap(admin).run(null);
        verify(admin).providers();
        verifyNoMoreInteractions(admin);
    }
    @Test void explicitBootstrapCoexistsWithLocalDefaultsAndExternalPlaceholders() {
        var admin = mock(AiAdminService.class);
        var local = mock(ProviderSummary.class);
        var placeholder = mock(ProviderSummary.class);
        when(local.code()).thenReturn("LOCAL_WHISPERX");
        when(placeholder.code()).thenReturn("DEEPSEEK");
        when(admin.providers()).thenReturn(List.of(local, placeholder));
        new DevAiBootstrap(admin).run(null);
        verify(admin).saveProvider("DEV_MOCK", "Lyreo development mock", null, null, true);
        verify(admin).saveRoute("STT", "DEV_MOCK", "mock", 0, false, true);
    }

    @Test void explicitBootstrapSkipsExistingCredentials() {
        var admin = mock(AiAdminService.class);
        var external = mock(ProviderSummary.class);
        when(external.configured()).thenReturn(true);
        when(admin.providers()).thenReturn(List.of(external));
        new DevAiBootstrap(admin).run(null);
        verify(admin).providers();
        verifyNoMoreInteractions(admin);
    }
}
