package com.lyreo.lesson.application.port;

import java.util.List;
import java.util.UUID;

public interface SourcePreviewPort {
    Preview create(String reference,long startMs,long endMs);
    Preview get(UUID id);
    byte[] audio(UUID id);
    record Preview(UUID id,UUID jobId,String status,String step,String error,long startMs,long endMs,List<Double> peaks,Double audioDurationMs) {}
}
