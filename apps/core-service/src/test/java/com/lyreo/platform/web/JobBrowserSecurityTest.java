package com.lyreo.platform.web;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lyreo.platform.jobs.application.BackgroundJobService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@SpringJUnitConfig(JobBrowserSecurityTest.Config.class)
class JobBrowserSecurityTest {
    @Configuration @EnableMethodSecurity static class Config {
        @Bean BackgroundJobService jobs() { return mock(BackgroundJobService.class); }
        @Bean JobController controller(BackgroundJobService service) { return new JobController(service); }
    }
    @Autowired JobController controller;
    @Autowired BackgroundJobService service;
    @Test @WithMockUser(roles="LEARNER") void learnerCannotBrowseJobs() throws Exception {
        var mvc=MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler()).build();
        mvc.perform(get("/api/v1/jobs")).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
    @Test @WithMockUser(roles="ADMIN") void adminCanBrowseJobs() throws Exception {
        when(service.list("", "", 0, 20)).thenReturn(new BackgroundJobService.Page(List.of(), 0, 0, 20));
        var mvc=MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler()).build();
        mvc.perform(get("/api/v1/jobs")).andExpect(status().isOk());
        verify(service).list("", "", 0, 20); clearInvocations(service);
    }
}
