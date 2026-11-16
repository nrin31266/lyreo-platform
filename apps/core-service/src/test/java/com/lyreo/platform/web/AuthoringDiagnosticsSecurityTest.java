package com.lyreo.platform.web;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lyreo.lesson.api.LessonAuthoringController;
import com.lyreo.lesson.application.authoring.LessonAuthoringService;
import com.lyreo.lesson.application.port.LessonCatalogPort;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@SpringJUnitConfig(AuthoringDiagnosticsSecurityTest.Config.class)
class AuthoringDiagnosticsSecurityTest {
    @Configuration @EnableMethodSecurity static class Config {
        @Bean LessonAuthoringService service() {return mock(LessonAuthoringService.class);}
        @Bean LessonAuthoringController controller(LessonAuthoringService service) {return new LessonAuthoringController(service,mock(LessonCatalogPort.class));}
    }
    @Autowired LessonAuthoringController controller;
    @Autowired LessonAuthoringService service;
    @Test @WithMockUser(roles="LEARNER") void learnerCannotReadPrivateOutputs() throws Exception {
        var mvc=MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler()).build();
        mvc.perform(get("/api/v1/admin/lesson-authoring/"+UUID.randomUUID()+"/jobs/"+UUID.randomUUID()+"/diagnostics")).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
    @Test @WithMockUser(roles="ADMIN") void adminCanReadJobScopedDiagnostics() throws Exception {
        var lesson=UUID.randomUUID();var job=UUID.randomUUID();when(service.diagnostics(lesson,job)).thenReturn(List.of());
        var mvc=MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler()).build();
        mvc.perform(get("/api/v1/admin/lesson-authoring/"+lesson+"/jobs/"+job+"/diagnostics")).andExpect(status().isOk());
        verify(service).diagnostics(lesson,job);clearInvocations(service);
    }
}
