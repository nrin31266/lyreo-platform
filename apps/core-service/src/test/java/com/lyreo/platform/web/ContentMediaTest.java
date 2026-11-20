package com.lyreo.platform.web;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.lyreo.assessment.api.ContentMediaController;
import com.lyreo.contracts.errors.RequestValidationException;
import com.lyreo.platform.content.AssetStore;
import com.lyreo.platform.storage.ObjectStoragePort;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

class ContentMediaTest {
    @Test void checksEachAssetOnceAndRunsIndependentStorageChecksConcurrently() {
        var store=spy(new AssetStore(mock(NamedParameterJdbcTemplate.class),mock(ObjectStoragePort.class),new ObjectMapper()));
        var first=UUID.randomUUID();var second=UUID.randomUUID();var bothStarted=new CountDownLatch(2);
        doAnswer(call->new AssetStore.Asset(call.getArgument(0),"audio/wav",10,"audio.wav",false)).when(store).get(any());
        doAnswer(call->{bothStarted.countDown();assertThat(bothStarted.await(2,TimeUnit.SECONDS)).isTrue();return !second.equals(call.getArgument(0));}).when(store).exists(any());
        assertThat(store.available(List.of(first,second,first))).containsOnlyKeys(first);
        verify(store).exists(first);verify(store).exists(second);
    }
    @Test void storageFailureDoesNotReportMissingFilesOrPassTheCheck() {
        var store=spy(new AssetStore(mock(NamedParameterJdbcTemplate.class),mock(ObjectStoragePort.class),new ObjectMapper()));
        UUID id=UUID.randomUUID();
        doReturn(new AssetStore.Asset(id,"audio/wav",10,"audio.wav",false)).when(store).get(id);
        doThrow(new IllegalStateException("Storage unavailable")).when(store).exists(id);
        assertThatThrownBy(()->store.available(List.of(id))).isInstanceOf(com.lyreo.contracts.errors.ServiceUnavailableException.class).hasMessageContaining("could not be checked");
    }
    @Test void servesByteRangesWithoutExposingStorageKeys() throws Exception {
        UUID id=UUID.randomUUID();var assets=mock(AssetStore.class);
        when(assets.get(id)).thenReturn(new AssetStore.Asset(id,"audio/wav",10,"sample.wav",false));
        when(assets.bytes(id)).thenReturn("0123456789".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var mvc=MockMvcBuilders.standaloneSetup(new ContentMediaController(assets)).build();
        mvc.perform(get("/api/v1/admin/content-media/"+id+"/file").header("Range","bytes=2-5"))
            .andExpect(status().isPartialContent()).andExpect(header().string("Content-Range","bytes 2-5/10"))
            .andExpect(header().string("Cache-Control","no-store")).andExpect(header().string("X-Content-Type-Options","nosniff"))
            .andExpect(content().string("2345"));
        mvc.perform(get("/api/v1/admin/content-media/"+id+"/file").header("Range","bytes=20-30")).andExpect(status().isRequestedRangeNotSatisfiable());
    }
    @Test void rejectsEmptyOversizedAndActiveFilesBeforeStorage() {
        var jdbc=mock(NamedParameterJdbcTemplate.class);var storage=mock(ObjectStoragePort.class);
        var store=new AssetStore(jdbc,storage,new ObjectMapper());
        assertThatThrownBy(()->store.upload("empty.wav",new byte[0],false,"admin")).isInstanceOf(RequestValidationException.class);
        assertThatThrownBy(()->store.upload("large.wav",new byte[30*1024*1024+1],false,"admin")).isInstanceOf(RequestValidationException.class);
        assertThatThrownBy(()->store.upload("fake.png","<svg onload='alert(1)'></svg>".getBytes(),false,"admin")).isInstanceOf(RequestValidationException.class);
        verifyNoInteractions(jdbc,storage);
    }
    @Test void decodesImagesBeforeWritingAndRejectsCorruptPixels() throws Exception {
        var jdbc=mock(NamedParameterJdbcTemplate.class);var storage=mock(ObjectStoragePort.class);
        var store=new AssetStore(jdbc,storage,new ObjectMapper());
        var output=new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(64,64,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",output);
        var bytes=output.toByteArray();
        UUID id=UUID.randomUUID();
        when(jdbc.query(anyString(),anyMap(),org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<AssetStore.Asset>>any()))
            .thenReturn(java.util.List.of(new AssetStore.Asset(id,"image/png",bytes.length,"image.png",false)));
        assertThat(store.upload("image.png",bytes,false,"admin").mimeType()).isEqualTo("image/png");
        verify(storage).put(anyString(),eq("image/png"),eq(bytes));
        clearInvocations(jdbc,storage);
        for(int index=0;index<bytes.length-12;index++) if(bytes[index]=='I' && bytes[index+1]=='D' && bytes[index+2]=='A' && bytes[index+3]=='T') {bytes[index+6]^=127;break;}
        assertThatThrownBy(()->store.upload("corrupt.png",bytes,false,"admin")).isInstanceOf(RequestValidationException.class);
        verifyNoInteractions(jdbc,storage);
    }
}
