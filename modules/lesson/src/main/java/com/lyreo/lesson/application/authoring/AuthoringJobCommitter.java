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
    public AuthoringJobCommitter(AuthoringRepository repository) {this.repository=repository;}
    @Transactional public void stage(BackgroundJob job,int index,java.util.List<com.lyreo.lesson.domain.authoring.AuthoringSentence> sentences) {
        var input=repository.jobInput(job.id());
        var current=repository.find(input.lessonId(),true).orElseThrow();
        repository.assertJobLease(job.id(),job.leaseOwner());
        if(current.publishedAt!=null || !Objects.equals(current.activeJobId,job.id())
            || current.transcriptRevision!=input.transcriptRevision())
            throw new PermanentJobException("AUTHORING_STALE","Discarded stale semantic chunk");
        repository.stageChunk(job.id(),index,sentences);
    }
    @Transactional public void commit(BackgroundJob job,AuthoringRepository.JobInput input,AuthoringLesson output) {
        var current=repository.find(input.lessonId(),true).orElseThrow();
        repository.assertJobLease(job.id(),job.leaseOwner());
        if(current.publishedAt!=null || !Objects.equals(current.activeJobId,job.id())
            || current.transcriptRevision!=input.transcriptRevision())
            throw new PermanentJobException("AUTHORING_STALE","Authoring output discarded because the draft changed");
        if("PREPARE".equals(input.operation())) {
            current.audioObjectKey=output.audioObjectKey;current.sentences=output.sentences;
            current.timingEvidence=output.timingEvidence;current.transcriptRevision++;current.timingStatus=output.timingStatus;current.status="TRANSCRIPT_REVIEW";
        } else if("ALIGN".equals(input.operation())) {
            current.sentences=output.sentences;current.timingStatus="VALID";current.karaokeReviewedRevision=null;
            current.status="TIMING_REVIEW";
        } else {
            current.sentences=output.sentences;current.semanticRevision=current.transcriptRevision;
            current.status="CONTENT_REVIEW";
        }
        current.activeJobId=null;repository.save(current);repository.completeJob(job.id());
    }
}
