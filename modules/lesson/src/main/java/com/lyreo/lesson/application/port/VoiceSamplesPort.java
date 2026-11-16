package com.lyreo.lesson.application.port;

import java.util.List;
import java.util.UUID;

public interface VoiceSamplesPort {
    Catalog catalog();
    UUID prepare();
    byte[] audio(String voiceId);
    record Voice(String id,String accent,String name,String gender,boolean sampleAvailable) {}
    record Catalog(String defaultVoice,List<Voice> voices,String sampleText,UUID sampleJobId,String sampleStatus,int sampleProgress,String sampleError) {}
}
