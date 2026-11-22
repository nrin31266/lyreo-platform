package com.lyreo.platform.web;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.lyreo.lexicon.api.LexiconAiController;
import com.lyreo.lexicon.application.*;
import com.lyreo.assessment.api.*;
import com.lyreo.assessment.application.QuestionAi;
import com.lyreo.platform.content.AiDraftRequests;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.method.support.*;
import org.springframework.core.MethodParameter;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.bind.support.WebDataBinderFactory;
class LibraryAiWireTest {
    final UUID id=UUID.randomUUID(),job=UUID.randomUUID(),sense=UUID.randomUUID();
    final LexiconAi lexicon=mock(LexiconAi.class);
    final QuestionAi question=mock(QuestionAi.class);
    AiDraftRequests.Request request() {return new AiDraftRequests.Request(UUID.randomUUID(),"lexicon",id,3,null,"TRANSLATE",Map.of(),null,Map.of(),job);}
    MockMvc mvc(Object controller) {
        return MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler())
            .setMessageConverters(new org.springframework.http.converter.json.JacksonJsonHttpMessageConverter((tools.jackson.databind.json.JsonMapper)TestMappers.productionJsonMapper()))
            .setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                public boolean supportsParameter(MethodParameter parameter) {return parameter.getParameterType().equals(Jwt.class);}
                public Object resolveArgument(MethodParameter parameter,ModelAndViewContainer container,NativeWebRequest request,WebDataBinderFactory binder) {return Jwt.withTokenValue("test").header("alg","none").subject("admin").build();}
            }).build();
    }
    @Test void translationDoesNotRequireTtsOnlyFields() throws Exception {
        when(lexicon.request(id,3,List.of(sense),"","TRANSLATE","admin")).thenReturn(request());
        mvc(new LexiconAiController(lexicon,mock(LexiconEditor.class),mock(AiDraftRequests.class))).perform(post("/api/v1/admin/lexicon/"+id+"/ai").contentType(MediaType.APPLICATION_JSON)
            .content("{\"revision\":3,\"senseIds\":[\""+sense+"\"],\"brief\":\"\",\"operation\":\"TRANSLATE\"}"))
            .andExpect(status().isAccepted()).andExpect(header().string("Location","/api/v1/jobs/"+job));
    }
    @Test void questionSuggestionsDoNotRequireTtsConfirmation() throws Exception {
        when(question.request(id,3,"SUGGEST",20,"","admin")).thenReturn(request());
        mvc(new QuestionAiController(question,mock(AiDraftRequests.class),mock(AssessmentLibrary.class))).perform(post("/api/v1/admin/assessment-items/"+id+"/ai").contentType(MediaType.APPLICATION_JSON)
            .content("{\"revision\":3,\"operation\":\"SUGGEST\",\"count\":20,\"brief\":\"\"}"))
            .andExpect(status().isAccepted());
    }
    @Test void questionTtsDoesNotRequireAnUnusedSuggestionCount() throws Exception {
        when(question.requestTts(id,3,"af_heart",true,"admin")).thenReturn(request());
        mvc(new QuestionAiController(question,mock(AiDraftRequests.class),mock(AssessmentLibrary.class))).perform(post("/api/v1/admin/assessment-items/"+id+"/ai").contentType(MediaType.APPLICATION_JSON)
            .content("{\"revision\":3,\"operation\":\"TTS\",\"voice\":\"af_heart\",\"reviewed\":true}"))
            .andExpect(status().isAccepted());
    }
}
