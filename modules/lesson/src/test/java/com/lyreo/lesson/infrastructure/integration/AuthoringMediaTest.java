package com.lyreo.lesson.infrastructure.integration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lyreo.platform.storage.ObjectStoragePort;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.UUID;
import javax.sound.sampled.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.ObjectMapper;

class AuthoringMediaTest {
    final NamedParameterJdbcTemplate jdbc=mock(NamedParameterJdbcTemplate.class);
    final ObjectStoragePort storage=mock(ObjectStoragePort.class);
    final AuthoringMedia media=new AuthoringMedia(jdbc,storage,new ObjectMapper(),"http://localhost:1","test-token");
    static byte[] wav(int seconds) throws Exception {
        var result=new ByteArrayOutputStream();var format=new AudioFormat(16000,16,1,true,false);
        AudioSystem.write(new AudioInputStream(new ByteArrayInputStream(new byte[16000*2*seconds]),format,16000L*seconds),AudioFileFormat.Type.WAVE,result);
        return result.toByteArray();
    }
    @Test void rejectsUnsupportedOversizedAndUndecodableUploadsBeforeStorage() {
        assertThatThrownBy(()->media.upload("recording.exe",new byte[]{1})).hasMessageContaining("MP3");
        assertThatThrownBy(()->media.upload("recording.wav",new byte[30*1024*1024+1])).hasMessageContaining("30 MiB");
        assertThatThrownBy(()->media.upload("recording.mp3",new byte[]{1,2,3})).hasMessageContaining("decoded");
        verifyNoInteractions(storage,jdbc);
    }
    @Test void uploadProbesAndDecodesRealAudio() throws Exception {
        var source=media.upload("recording.wav",wav(2));
        assertThat(source.durationMs()).isEqualTo(2000);
        assertThat(source.contentType()).isEqualTo("audio/wav");
        verify(storage).put(contains(source.id().toString()),eq("audio/wav"),any());
        verify(jdbc).update(contains("INSERT INTO lesson_audio_source"),anyMap());
    }
    @Test void failedDatabaseWriteRemovesOnlyTheNewUpload() throws Exception {
        when(jdbc.update(anyString(),anyMap())).thenThrow(new IllegalStateException("database unavailable"));
        assertThatThrownBy(()->media.upload("recording.wav",wav(1))).hasMessageContaining("database unavailable");
        verify(storage).delete(startsWith("sources/audio/"));
    }
    @Test void generatedSpeechIsNormalizedAndBounded() throws Exception {
        var audio=media.storeTts(UUID.randomUUID(),UUID.randomUUID(),wav(1));
        assertThat(audio.durationMs()).isEqualTo(1000);
        verify(storage).put(eq(audio.key()),eq("audio/wav"),any());
        assertThatThrownBy(()->media.storeTts(UUID.randomUUID(),UUID.randomUUID(),wav(601))).hasMessageContaining("10 minutes");
    }
}
