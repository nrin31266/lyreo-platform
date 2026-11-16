package com.lyreo.lesson.application.authoring;

import com.lyreo.lesson.application.port.AuthoringRepository;
import com.lyreo.lesson.domain.authoring.AuthoringLesson;
import com.lyreo.platform.jobs.domain.BackgroundJob;
import com.lyreo.platform.jobs.application.PermanentJobException;
import java.util.Objects;
import org.springframework.transaction.annotation.Transactional;

/** The same short transaction fences worker ownership and publishes a checkpoint. */
public class AuthoringJobCommitter {
    private final AuthoringRepository repository;
    private final LessonAuthoringService authoring;
    public AuthoringJobCommitter(AuthoringRepository repository,LessonAuthoringService authoring) {this.repository=repository;this.authoring=authoring;}
    @Transactional public void commit(BackgroundJob job,AuthoringRepository.JobInput input,AuthoringLesson output) {
        var current=repository.find(input.lessonId(),true).orElseThrow();
        repository.assertJobLease(job.id(),job.leaseOwner());
        if(repository.jobInput(job.id()).completed()) return;
        if(current.publishedAt!=null || !Objects.equals(current.activeJobId,job.id())
            || current.transcriptRevision!=input.transcriptRevision())
            throw new PermanentJobException("AUTHORING_STALE","Authoring output discarded because the draft changed");
        if("PREPARE".equals(input.operation())) {
            current.audioObjectKey=output.audioObjectKey;current.sentences=output.sentences;
            current.timingEvidence=output.timingEvidence;current.transcriptRevision++;current.timingStatus=output.timingStatus;current.status="TRANSCRIPT_REVIEW";
        } else if("TTS".equals(input.operation())) {
            current.audioObjectKey=output.audioObjectKey;current.endMs=output.endMs;current.sentences=output.sentences;
            current.timingStatus="MISSING";current.timingEvidence=java.util.Map.of();
            current.approvedRevision=null;current.karaokeReviewedRevision=null;current.semanticRevision=null;current.status="TRANSCRIPT_REVIEW";
        } else if("ALIGN".equals(input.operation())) {
            current.sentences=output.sentences;current.timingStatus="VALID";current.karaokeReviewedRevision=null;
            if(current.sentences.stream().anyMatch(sentence->sentence.hidden)) current.approvedRevision=null;
            current.sentences.forEach(sentence->sentence.hidden=false);
            current.status="TIMING_REVIEW";
        } else {
            throw new PermanentJobException("AUTHORING_OPERATION_REMOVED","This authoring operation is no longer supported");
        }
        current.activeJobId=null;repository.save(current);
        if("PREPARE".equals(input.operation()) && "AUDIO".equals(current.sourceType) && !current.sentences.isEmpty())
            repository.renameAutomatic(current.id,LessonAuthoringService.automaticTitle(current.sentences.getFirst().text));
        repository.completeJob(job.id());
        if("PREPARE".equals(input.operation()) || "TTS".equals(input.operation())) authoring.alignPrepared(current);
    }
}
