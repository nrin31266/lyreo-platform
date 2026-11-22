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
            current.semanticRevision=null;
        } else if("CONTENT".equals(input.operation())) {
            current.status="CONTENT_REVIEW";current.semanticRevision=null;
        } else {
            throw new PermanentJobException("AUTHORING_OPERATION_REMOVED","This authoring operation is no longer supported");
        }
        current.activeJobId=null;repository.save(current);
        repository.jobResult(job.id(),com.lyreo.lesson.domain.authoring.AuthoringJobResult.capture(current));
        if("PREPARE".equals(input.operation()) && "AUDIO".equals(current.sourceType) && !current.sentences.isEmpty())
            repository.renameAutomatic(current.id,LessonAuthoringService.automaticTitle(current.sentences.getFirst().text));
        repository.completeJob(job.id());
        if("PREPARE".equals(input.operation()) || "TTS".equals(input.operation())) authoring.alignPrepared(current);
        if("ALIGN".equals(input.operation())) authoring.continueContent(current);
    }

    @Transactional public void contentCheckpoint(BackgroundJob job,AuthoringRepository.JobInput input,
        AuthoringLesson output,java.util.List<Integer> indexes,java.util.Set<String> features) {
        var current=repository.find(input.lessonId(),true).orElseThrow();
        repository.assertJobLease(job.id(),job.leaseOwner());
        if(current.publishedAt!=null || !Objects.equals(current.activeJobId,job.id())
            || current.transcriptRevision!=input.transcriptRevision() || !current.options.equals(output.options))
            throw new PermanentJobException("AUTHORING_STALE","Content discarded because the draft changed");
        for(int index:indexes) {
            var source=output.sentences.get(index);var target=current.sentences.get(index);
            for(String feature:features) {
                if(target.manualFields.contains(feature)) continue;
                switch(feature) {
                    case "TRANSLATION" -> target.translation=source.translation;
                    case "IPA" -> {target.ipaRaw=source.ipaRaw;target.ipaDisplayVersion=source.ipaDisplayVersion;target.ipa=source.ipa;target.ipaAccent=source.ipaAccent;
                        target.ipaVersion=source.ipaVersion;target.ipaNeedsReview=source.ipaNeedsReview;target.ipaReviewIssues=source.ipaReviewIssues;}
                    case "LEXICAL" -> target.vocabulary=source.vocabulary;
                    case "GRAMMAR" -> target.grammar=source.grammar;
                    case "DICTATION" -> {target.dictationSelected=source.dictationSelected;target.dictationEligible=!target.words.isEmpty();}
                    case "SHADOWING" -> {target.shadowingSelected=source.shadowingSelected;target.shadowingEligible=!target.words.isEmpty();}
                    case "PROFILE" -> {target.cefrSuggestion=source.cefrSuggestion;target.contentTypeSuggestion=source.contentTypeSuggestion;}
                    default -> throw new IllegalArgumentException("Unsupported content feature");
                }
                if(source.contentKeys.containsKey(feature)) target.contentKeys.put(feature,source.contentKeys.get(feature));
            }
            target.contentWarnings=source.contentWarnings;target.exclusions=source.exclusions;
            if(!target.clozeManual) target.cloze=com.lyreo.lesson.domain.authoring.AuthoringCloze.generate(target);
            target.contentStale=false;
        }
        if(features.contains("PROFILE") && !current.profile.manual) current.profile=com.lyreo.lesson.domain.authoring.AuthoringProfile.aggregate(current);
        current.semanticRevision=null;current.status="CONTENT_REVIEW";
        repository.save(current);
        repository.jobResult(job.id(),com.lyreo.lesson.domain.authoring.AuthoringJobResult.capture(current));
    }
}
