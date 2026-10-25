package com.lyreo.lesson.application.authoring;

import com.lyreo.contracts.errors.RequestValidationException;
import com.lyreo.contracts.errors.ResourceNotFoundException;
import com.lyreo.contracts.errors.StateConflictException;
import com.lyreo.lesson.application.port.AuthoringRepository;
import com.lyreo.lesson.application.port.AuthoringSourcePort;
import com.lyreo.lesson.domain.authoring.AuthoringLesson;
import com.lyreo.lesson.domain.authoring.AuthoringSentence;
import com.lyreo.lesson.domain.authoring.AuthoringText;
import com.lyreo.platform.jobs.application.BackgroundJobService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

public class LessonAuthoringService {
    public static final String JOB_TYPE="LESSON_AUTHORING";
    private final AuthoringRepository repository;
    private final AuthoringSourcePort source;
    private final BackgroundJobService jobs;
    public LessonAuthoringService(AuthoringRepository repository,AuthoringSourcePort source,BackgroundJobService jobs) {
        this.repository=repository; this.source=source; this.jobs=jobs;
    }
    public Inspect inspect(String reference) {
        var info=source.inspect(reference);
        return new Inspect(info.videoId(),info.title(),info.durationMs(),info.embedAllowed(),repository.usedRanges(info.videoId()));
    }
    @Transactional public Accepted create(String title,String reference,long start,long end,boolean rights) {
        require(title!=null && !title.isBlank() && title.length()<=500,"Title is required (maximum 500 characters)");
        require(rights,"Confirm operator responsibility for source use before preparing a source");
        var info=source.inspect(reference);
        require(info.embedAllowed(),"This source cannot be embedded");
        require(start>=0 && end>start && end<=info.durationMs(),"Select a range within the source duration");
        require(end-start<=30*60_000,"V1 supports a maximum selected range of 30 minutes");
        var lesson=new AuthoringLesson();
        lesson.id=UUID.randomUUID(); lesson.title=title.strip(); lesson.videoId=info.videoId();
        lesson.sourceReference="https://www.youtube.com/watch?v="+info.videoId();
        lesson.startMs=start; lesson.endMs=end; lesson.status="TRANSCRIPT_REVIEW";lesson.timingStatus="MISSING";
        repository.create(lesson);
        return enqueue(lesson,"PREPARE");
    }
    public AuthoringLesson get(UUID id) {return find(id,false);}
    private AuthoringLesson find(UUID id,boolean lock) {
        return repository.find(id,lock).orElseThrow(()->new ResourceNotFoundException("Authoring lesson not found"));
    }
    private AuthoringLesson editable(UUID id,long revision) {
        var lesson=find(id,true);
        if(lesson.publishedAt!=null) throw new StateConflictException("Published lessons are read-only");
        if(lesson.revision!=revision) throw new StateConflictException("Draft changed; refresh before saving");
        return lesson;
    }
    @Transactional public AuthoringLesson transcript(UUID id,long revision,List<SentenceInput> input) {
        var lesson=editable(id,revision);
        require(input!=null && !input.isEmpty() && input.size()<=1000,"Provide 1 to 1000 sentences");
        var previous=new HashMap<UUID,AuthoringSentence>();
        lesson.sentences.forEach(s->previous.put(s.id,s));
        var seen=new HashSet<UUID>();
        var next=new ArrayList<AuthoringSentence>();
        int length=0;
        for(var item:input) {
            require(item!=null && item.text()!=null && !AuthoringText.tokens(item.text()).isEmpty(),"Sentence needs lexical text");
            require(item.text().length()<=10000,"Sentence exceeds 10000 characters");
            require(item.usage()!=null && Set.of("LEARN","WATCH_ONLY","SKIP").contains(item.usage()),"Invalid sentence usage");
            if(item.id()!=null) require(previous.containsKey(item.id()),"Sentence identity does not belong to this draft");
            UUID sentenceId=item.id()==null ? UUID.randomUUID() : item.id();
            require(seen.add(sentenceId),"Duplicate sentence identity");
            var sentence=new AuthoringSentence(sentenceId,item.text().strip(),item.usage());
            var old=previous.get(sentenceId);
            if(old!=null) {sentence.coarseStartMs=old.coarseStartMs;sentence.coarseEndMs=old.coarseEndMs;}
            next.add(sentence);length+=item.text().length();
        }
        require(length<=200_000,"Transcript exceeds supported size");
        if(sameTranscript(lesson.sentences,next)) return lesson;
        var observed=lesson.sentences.stream().flatMap(s->s.words.stream())
            .map(w->new AuthoringText.ObservedWord(w.text(),w.startMs(),w.endMs())).toList();
        String oldLexical=lesson.sentences.stream().map(s->AuthoringText.lexicalSequence(s.text)).reduce((a,b)->a+" "+b).orElse("");
        String nextLexical=next.stream().map(s->AuthoringText.lexicalSequence(s.text)).reduce((a,b)->a+" "+b).orElse("");
        if(oldLexical.equals(nextLexical) && !observed.isEmpty()) {
            try {AuthoringText.mapTiming(next,observed,lesson.endMs-lesson.startMs);}
            catch(RequestValidationException invalid) {lesson.timingStatus="STALE";next.forEach(s->s.words=List.of());}
        } else {
            retainCoarseBounds(lesson.sentences,next);
            lesson.timingStatus="STALE";
        }
        boolean aligned=Set.of("VALID","DRAFT_VALID").contains(lesson.timingStatus);
        next.forEach(s->{s.issues=AuthoringText.issues(s);AuthoringText.eligibility(s,aligned);});
        lesson.sentences=next;lesson.transcriptRevision++;
        lesson.approvedRevision=null;lesson.karaokeReviewedRevision=null;lesson.semanticRevision=null;
        lesson.activeJobId=null;lesson.status="TRANSCRIPT_REVIEW";
        repository.save(lesson);return lesson;
    }
    private static boolean sameTranscript(List<AuthoringSentence> old,List<AuthoringSentence> next) {
        if(old.size()!=next.size()) return false;
        for(int i=0;i<old.size();i++) if(!old.get(i).id.equals(next.get(i).id)
            || !old.get(i).text.equals(next.get(i).text) || !old.get(i).usage.equals(next.get(i).usage)) return false;
        return true;
    }
    private static void retainCoarseBounds(List<AuthoringSentence> old,List<AuthoringSentence> next) {
        var positions=new HashMap<UUID,Integer>();
        for(int i=0;i<old.size();i++) positions.put(old.get(i).id,i);
        for(int begin=0;begin<next.size();) {
            Integer oldBegin=positions.get(next.get(begin).id);
            if(oldBegin==null) {begin++;continue;}
            int end=begin+1;
            while(end<next.size() && !positions.containsKey(next.get(end).id)) end++;
            int oldEnd=end<next.size() ? positions.get(next.get(end).id) : old.size();
            if(oldEnd>oldBegin) {
                var evidence=old.subList(oldBegin,oldEnd);
                var words=evidence.stream().flatMap(sentence->sentence.words.isEmpty() ? sentence.sourceWords.stream()
                    : sentence.words.stream().map(word->new AuthoringText.ObservedWord(word.text(),word.startMs(),word.endMs())))
                    .distinct().toList();
                next.get(begin).sourceWords=words;
                if(evidence.stream().allMatch(sentence->sentence.coarseStartMs!=null && sentence.coarseEndMs!=null)) {
                    long start=evidence.stream().mapToLong(sentence->sentence.coarseStartMs).min().orElseThrow();
                    long finish=evidence.stream().mapToLong(sentence->sentence.coarseEndMs).max().orElseThrow();
                    var descendants=next.subList(begin,end);
                    if(descendants.size()==1 || finish-start<=115_000) {
                        // A shared short observed window can safely align edits at the split boundary.
                        for(var sentence:descendants) {sentence.coarseStartMs=start;sentence.coarseEndMs=finish;}
                    } else if(!splitObservedBounds(descendants,words,start,finish)) {
                        for(var sentence:descendants) {sentence.coarseStartMs=null;sentence.coarseEndMs=null;}
                    }
                }
            }
            begin=end;
        }
    }
    private static boolean splitObservedBounds(List<AuthoringSentence> descendants,List<AuthoringText.ObservedWord> words,
        long start,long finish) {
        var observedBoundaries=new HashMap<String,Integer>();
        String previousToken=null;
        long previousEnd=start;
        for(int i=0;i<words.size();i++) {
            var word=words.get(i);
            var tokens=AuthoringText.tokens(word.text());
            if(tokens.isEmpty() || word.startMs()<previousEnd || word.endMs()<=word.startMs() || word.endMs()>finish) return false;
            for(int token=0;token<tokens.size();token++) {
                String currentToken=AuthoringText.lexical(tokens.get(token).text());
                if(previousToken!=null) observedBoundaries.merge(previousToken+" "+currentToken,token==0 ? i : -1,(a,b)->-1);
                previousToken=currentToken;
            }
            previousEnd=word.endMs();
        }
        var boundaries=new ArrayList<Integer>();
        int previous=0;
        for(int i=1;i<descendants.size();i++) {
            var left=AuthoringText.tokens(descendants.get(i-1).text);
            var right=AuthoringText.tokens(descendants.get(i).text);
            String boundary=AuthoringText.lexical(left.getLast().text())+" "+AuthoringText.lexical(right.getFirst().text());
            int match=observedBoundaries.getOrDefault(boundary,-1);
            if(match<=previous) return false;
            boundaries.add(match);previous=match;
        }
        int from=0;
        for(int i=0;i<descendants.size();i++) {
            int to=i<boundaries.size() ? boundaries.get(i) : words.size();
            var sentence=descendants.get(i);
            sentence.coarseStartMs=i==0 ? start : words.get(from).startMs();
            sentence.coarseEndMs=i==descendants.size()-1 ? finish : words.get(to-1).endMs();
            sentence.sourceWords=List.copyOf(words.subList(from,to));
            from=to;
        }
        return true;
    }
    @Transactional public AuthoringLesson approve(UUID id,long revision) {
        var lesson=editable(id,revision);
        require(!lesson.sentences.isEmpty(),"Prepare the transcript first");
        require(lesson.sentences.stream().anyMatch(s->"LEARN".equals(s.usage)),"At least one Learn sentence is required");
        lesson.approvedRevision=lesson.transcriptRevision;
        lesson.status="TIMING_REVIEW";repository.save(lesson);return lesson;
    }
    @Transactional public Accepted align(UUID id,long revision) {
        var lesson=editable(id,revision);
        require(Objects.equals(lesson.approvedRevision,lesson.transcriptRevision),"Approve the current transcript before alignment");

        alignmentSegments(lesson);
        return enqueue(lesson,"ALIGN");
    }
    @Transactional public AuthoringLesson karaoke(UUID id,long revision) {
        var lesson=editable(id,revision);
        require(Objects.equals(lesson.approvedRevision,lesson.transcriptRevision),"Approve the current transcript first");
        require(Set.of("VALID","DRAFT_VALID").contains(lesson.timingStatus),"Resolve timing issues before confirming karaoke review");
        validateTiming(lesson);
        lesson.karaokeReviewedRevision=lesson.transcriptRevision;
        repository.save(lesson);return lesson;
    }
    @Transactional public Accepted generate(UUID id,long revision) {
        var lesson=editable(id,revision);
        readyForSemantic(lesson);return enqueue(lesson,"SEMANTIC");
    }
    @Transactional public Accepted prepare(UUID id,long revision) {
        var lesson=editable(id,revision);
        require(lesson.sentences.isEmpty(),"Preparation cannot overwrite an edited transcript");
        return enqueue(lesson,"PREPARE");
    }
    private Accepted enqueue(AuthoringLesson lesson,String operation) {
        if(lesson.activeJobId!=null) {
            var current=jobs.findById(lesson.activeJobId);
            if(current.isPresent() && Set.of("QUEUED","RUNNING","RETRY_WAIT","CANCEL_REQUESTED").contains(current.get().status().name()))
                throw new StateConflictException("An authoring job is already active");
        }
        UUID jobId=jobs.enqueue(JOB_TYPE,"lesson",lesson.id,50,3,"{\"operation\":\""+operation+"\"}");
        repository.addJob(lesson.id,jobId,operation,lesson.transcriptRevision);
        lesson.activeJobId=jobId;repository.save(lesson);
        return new Accepted(lesson.id,jobId);
    }
    @Transactional public AuthoringLesson candidates(UUID id,long revision,List<UUID> dictation,List<UUID> shadowing) {
        var lesson=editable(id,revision);
        require(Objects.equals(lesson.semanticRevision,lesson.transcriptRevision),"Generate current semantic content first");
        requireNoActiveJob(lesson);
        require(dictation!=null && shadowing!=null,"Provide both candidate selections");
        var d=new HashSet<>(dictation);var s=new HashSet<>(shadowing);
        require(d.size()==dictation.size() && s.size()==shadowing.size(),"Duplicate candidate selection");
        var known=new HashSet<UUID>();
        for(var sentence:lesson.sentences) {
            known.add(sentence.id);
            require(!d.contains(sentence.id) || sentence.dictationEligible,"Ineligible dictation sentence selected");
            require(!s.contains(sentence.id) || sentence.shadowingEligible,"Ineligible shadowing sentence selected");
            sentence.dictationSelected=d.contains(sentence.id);sentence.shadowingSelected=s.contains(sentence.id);
        }
        require(known.containsAll(d) && known.containsAll(s),"Candidate does not belong to the lesson");
        repository.save(lesson);return lesson;
    }
    @Transactional public AuthoringLesson publish(UUID id,long revision) {
        var lesson=editable(id,revision);readyForSemantic(lesson);
        require(Objects.equals(lesson.semanticRevision,lesson.transcriptRevision),"Semantic output is stale or missing");
        requireNoActiveJob(lesson);
        validateTiming(lesson);
        for(var sentence:lesson.sentences) {
            require(!sentence.dictationSelected || sentence.dictationEligible,"Invalid dictation membership");
            require(!sentence.shadowingSelected || sentence.shadowingEligible,"Invalid shadowing membership");
            require(!"LEARN".equals(sentence.usage) || (sentence.translation!=null && !sentence.translation.isBlank()),"Learn translation is missing");
        }
        repository.publish(lesson);
        lesson.status="PUBLISHED";lesson.publishedAt=Instant.now();repository.save(lesson);
        return lesson;
    }
    private void requireNoActiveJob(AuthoringLesson lesson) {
        if(lesson.activeJobId==null) return;
        var job=jobs.findById(lesson.activeJobId);
        if(job.isPresent() && Set.of("QUEUED","RUNNING","RETRY_WAIT","CANCEL_REQUESTED").contains(job.get().status().name()))
            throw new StateConflictException("Wait for the active authoring job before changing candidates or publishing");
        lesson.activeJobId=null;
    }
    public static void readyForSemantic(AuthoringLesson lesson) {
        require(Objects.equals(lesson.approvedRevision,lesson.transcriptRevision),"Transcript approval is stale");
        require(Objects.equals(lesson.karaokeReviewedRevision,lesson.transcriptRevision),"Review the current karaoke timing first");
        require(Set.of("VALID","DRAFT_VALID").contains(lesson.timingStatus),"Valid reviewed timing is required");
    }
    public static void validateTiming(AuthoringLesson lesson) {
        long previousEnd=-1;
        for(var sentence:lesson.sentences) {
            require(!sentence.words.isEmpty(),"Missing timing for a sentence");
            for(var word:sentence.words) {
                require(word.startMs()>=previousEnd && word.endMs()>word.startMs()
                    && word.endMs()<=lesson.endMs-lesson.startMs,"Timing has overlap, zero duration, or range errors");
                previousEnd=word.endMs();
            }
        }
    }
    public static List<Map<String,Object>> alignmentSegments(AuthoringLesson lesson) {
        var windows=new ArrayList<Map<String,Object>>();
        var text=new StringBuilder();long begin=-1,end=-1,previousStart=-1,previousEnd=-1;
        for(var sentence:lesson.sentences) {
            require(sentence.coarseStartMs!=null && sentence.coarseEndMs!=null,"No safe ASR word boundary for alignment; create a new draft with a shorter source range or split before lexical edits");
            require(sentence.coarseStartMs>=previousStart && sentence.coarseEndMs>=previousEnd
                && sentence.coarseStartMs>=0 && sentence.coarseEndMs<=lesson.endMs-lesson.startMs,"Unsafe or reordered ASR boundaries for alignment");
            previousStart=sentence.coarseStartMs;previousEnd=sentence.coarseEndMs;
            require(sentence.coarseEndMs>sentence.coarseStartMs && sentence.coarseEndMs-sentence.coarseStartMs<=115_000,
                "A sentence exceeds the safe alignment window; split it before approval");
            if(begin>=0 && sentence.coarseEndMs-begin>115_000) {
                windows.add(Map.of("text",text.toString().strip(),"start_ms",begin,"end_ms",end));
                text.setLength(0);begin=-1;
            }
            if(begin<0) begin=sentence.coarseStartMs;
            end=sentence.coarseEndMs;text.append(sentence.text).append(' ');
        }
        if(begin>=0) windows.add(Map.of("text",text.toString().strip(),"start_ms",begin,"end_ms",end));
        require(!windows.isEmpty(),"No alignment windows");return windows;
    }
    public static void require(boolean valid,String message) {if(!valid)throw new RequestValidationException(message);}
    public record SentenceInput(UUID id,String text,String usage) {}
    public record Inspect(String videoId,String title,long durationMs,boolean embedAllowed,List<AuthoringRepository.UsedRange> usedRanges) {}
    public record Accepted(UUID lessonId,UUID jobId) {}
}
