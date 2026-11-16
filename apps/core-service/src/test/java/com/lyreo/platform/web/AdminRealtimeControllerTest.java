package com.lyreo.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lyreo.notification.api.AdminRealtimeController;
import com.lyreo.notification.application.port.JobProgressFeed;
import com.lyreo.notification.infrastructure.InMemoryJobProgressFeed;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@SpringJUnitConfig(AdminRealtimeControllerTest.Config.class)
class AdminRealtimeControllerTest {
    @Configuration @EnableMethodSecurity static class Config {
        @Bean InMemoryJobProgressFeed feed(){return new InMemoryJobProgressFeed();}
        @Bean AdminRealtimeController realtime(JobProgressFeed feed){return new AdminRealtimeController(feed);}
    }
    @Autowired AdminRealtimeController controller;
    @Autowired InMemoryJobProgressFeed feed;

    @Test @WithMockUser(roles="ADMIN") void connectsAndDeliversCommittedJobNotifications() throws Exception {
        var mvc=MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler()).build();
        var result=mvc.perform(get("/api/v1/admin/realtime/jobs")).andExpect(status().isOk())
            .andExpect(request().asyncStarted()).andReturn();
        assertThat(result.getResponse().getContentAsString()).contains("event:connected","SSE");
        var id=UUID.randomUUID();feed.jobProgress(id,"SUCCEEDED","CHECKPOINT_SAVED",100);
        assertThat(result.getResponse().getContentAsString()).contains("event:job-progress",id.toString(),"SUCCEEDED");
        result.getRequest().getAsyncContext().complete();
    }
    @Test @WithMockUser(roles="LEARNER") void learnerCannotSubscribeToAdminFeed() throws Exception {
        var mvc=MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler()).build();
        mvc.perform(get("/api/v1/admin/realtime/jobs")).andExpect(status().isForbidden());
    }
}
