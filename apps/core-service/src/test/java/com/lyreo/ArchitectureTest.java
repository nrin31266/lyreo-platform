package com.lyreo;

import static org.assertj.core.api.Assertions.assertThat;

import com.lyreo.ai.application.execution.AiExecutionException;
import com.lyreo.ai.application.execution.AiExecutionResult;
import com.lyreo.ai.application.execution.AiInvocationService;
import com.lyreo.ai.application.port.AiAdminRepository;
import com.lyreo.ai.application.port.AiExecutionGateway;
import com.lyreo.ai.application.port.AiRouteRepository;
import com.lyreo.ai.application.routing.AiRoutingSnapshotService;
import com.lyreo.ai.domain.AiCapability;
import com.lyreo.ai.domain.AiRoute;
import com.lyreo.identity.application.AppUserProvisioningService;
import com.lyreo.identity.application.ProvisionedUser;
import com.lyreo.identity.application.port.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;

class ArchitectureTest {

    @Test
    void verifiesModularStructure() {
        ApplicationModules.of(LyreoApplication.class).verify();
    }

    @Test
    void verifiesExactExposedAndInternalTypes() {
        ApplicationModules modules = ApplicationModules.of(LyreoApplication.class);

        ApplicationModule identity = modules.getModuleByType(AppUserProvisioningService.class)
            .orElseThrow(() -> new AssertionError("identity module not found"));
        assertThat(identity.isExposed(AppUserProvisioningService.class)).isTrue();
        assertThat(identity.isExposed(ProvisionedUser.class)).isTrue();
        assertThat(identity.isExposed(AppUserRepository.class)).isFalse();

        ApplicationModule ai = modules.getModuleByType(AiInvocationService.class)
            .orElseThrow(() -> new AssertionError("ai module not found"));
        assertThat(ai.isExposed(AiInvocationService.class)).isTrue();
        assertThat(ai.isExposed(AiRoutingSnapshotService.class)).isTrue();
        assertThat(ai.isExposed(AiExecutionResult.class)).isTrue();
        assertThat(ai.isExposed(AiExecutionException.class)).isTrue();
        assertThat(ai.isExposed(AiCapability.class)).isTrue();

        assertThat(ai.isExposed(AiExecutionGateway.class)).isFalse();
        assertThat(ai.isExposed(AiRouteRepository.class)).isFalse();
        assertThat(ai.isExposed(AiAdminRepository.class)).isFalse();
        assertThat(ai.isExposed(AiRoute.class)).isFalse();
    }
}
