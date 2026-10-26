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
        when(admin.providers()).thenReturn(List.of(mock(ProviderSummary.class)));
        new DevAiBootstrap(admin).run(null);
        verify(admin).providers();
        verifyNoMoreInteractions(admin);
    }
    @Test void explicitBootstrapSeedsOnlyAnEmptyCatalog() {
        var admin = mock(AiAdminService.class);
        when(admin.providers()).thenReturn(List.of());
        new DevAiBootstrap(admin).run(null);
        verify(admin).saveProvider("DEV_MOCK", "Lyreo development mock", null, null, true);
        verify(admin).saveRoute("STT", "DEV_MOCK", "mock", 10, false, true);
    }
}
