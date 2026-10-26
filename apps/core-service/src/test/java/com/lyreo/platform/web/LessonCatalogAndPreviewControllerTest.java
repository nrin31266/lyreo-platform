package com.lyreo.platform.web;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.lyreo.lesson.api.LessonCatalogController;
import com.lyreo.lesson.api.SourcePreviewController;
import com.lyreo.lesson.application.port.LessonCatalogPort;
import com.lyreo.lesson.application.port.SourcePreviewPort;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class LessonCatalogAndPreviewControllerTest {
    @Configuration(proxyBeanMethods=false) @EnableMethodSecurity
    static class MethodSecurity {}

    @Test void learnerCannotReadPreviewMediaOrModifyClassification() {
        var catalog=mock(LessonCatalogPort.class);var previews=mock(SourcePreviewPort.class);
        try(var context=new AnnotationConfigApplicationContext()) {
            context.register(MethodSecurity.class);
            context.registerBean(LessonCatalogPort.class,()->catalog);
            context.registerBean(SourcePreviewPort.class,()->previews);
            context.registerBean(LessonCatalogController.class);
            context.registerBean(SourcePreviewController.class);
            context.refresh();
            var auth=UsernamePasswordAuthenticationToken.authenticated("learner","",List.of(new SimpleGrantedAuthority("ROLE_LEARNER")));
            SecurityContextHolder.getContext().setAuthentication(auth);
            assertThatThrownBy(()->context.getBean(SourcePreviewController.class).audio(UUID.randomUUID())).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(()->context.getBean(LessonCatalogController.class).update(UUID.randomUUID(),new LessonCatalogController.Metadata(null,List.of())))
                .isInstanceOf(AccessDeniedException.class);
            verifyNoInteractions(catalog,previews);
        } finally {SecurityContextHolder.clearContext();}
    }
    @Test void previewMediaSupportsByteRangesWithoutExposingStorageUrls() throws Exception {
        var previews=mock(SourcePreviewPort.class);UUID id=UUID.randomUUID();
        when(previews.audio(id)).thenReturn(new byte[]{1,2,3,4,5,6});
        var mvc=MockMvcBuilders.standaloneSetup(new SourcePreviewController(previews)).setControllerAdvice(new ApiExceptionHandler()).build();
        mvc.perform(get("/api/v1/admin/lesson-source-previews/"+id+"/audio").header("Range","bytes=2-4"))
            .andExpect(status().isPartialContent()).andExpect(header().string("Content-Range","bytes 2-4/6"))
            .andExpect(header().string("Cache-Control","no-store")).andExpect(content().bytes(new byte[]{3,4,5}));
        mvc.perform(post("/api/v1/admin/lesson-source-previews").contentType("application/json").content("{\"sourceReference\":\"\"}"))
            .andExpect(status().isBadRequest());
        verify(previews,never()).create(anyString(),anyLong(),anyLong());
    }
}
