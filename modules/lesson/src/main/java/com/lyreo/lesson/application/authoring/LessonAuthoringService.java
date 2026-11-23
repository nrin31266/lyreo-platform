package com.lyreo.lesson.application.authoring;

import com.lyreo.contracts.errors.RequestValidationException;
import com.lyreo.contracts.errors.ResourceNotFoundException;
import com.lyreo.contracts.errors.StateConflictException;
import com.lyreo.lesson.application.port.AuthoringRepository;
import com.lyreo.lesson.application.port.AuthoringSourcePort;
import com.lyreo.lesson.domain.authoring.AuthoringLesson;
import com.lyreo.lesson.domain.authoring.AuthoringSentence;
import com.lyreo.lesson.domain.authoring.AuthoringText;
import com.lyreo.lesson.domain.authoring.AuthoringContent;
import com.lyreo.lesson.domain.authoring.CaptionCueText;
import com.lyreo.lesson.domain.authoring.AuthoringOptions;
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
    private boolean reuseDraftTiming;
    public void setReuseDraftTiming(boolean enabled) {this.reuseDraftTiming=enabled;}
    private final AuthoringRepository repository;
    private final AuthoringSourcePort source;
    private final BackgroundJobService jobs;
    public LessonAuthoringService(AuthoringRepository repository,AuthoringSourcePort source,BackgroundJobService jobs) {
        this.repository=repository; this.source=source; this.jobs=jobs;
    }
    public Inspect inspect(String reference) {
        var info=source.inspect(reference);
        return new Inspect(info.videoId(),info.title(),info.durationMs(),info.embedAllowed(),repository.usedRanges(info.videoId()),info.thumbnailUrl(),info.englishCaptions());
    }
    @Transactional public Accepted create(String title,String reference,long start,long end) {return create(title,reference,start,end,null);}
    @Transactional public Accepted create(String title,String reference,long start,long end,AuthoringOptions options) {
        return create(title,reference,start,end,options,false,null);
    }
    @Transactional public Accepted create(String title,String reference,long start,long end,AuthoringOptions options,
                                           boolean preferEnglishCaptions,UploadedCaptions.File captionFile) {
        require(title==null || title.length()<=500,"Title supports a maximum of 500 characters");
        var info=source.inspect(reference);
        require(info.embedAllowed(),"This source cannot be embedded");
        require(start>=0 && end>start && end<=info.durationMs(),"Select a range within the source duration");
        require(end-start<=10*60_000,"A lesson supports a maximum selected range of 10 minutes");
        var lesson=new AuthoringLesson();
        lesson.id=UUID.randomUUID(); lesson.automaticTitle=title==null || title.isBlank();
        lesson.title=lesson.automaticTitle ? automaticTitle(info.title()) : title.strip(); lesson.videoId=info.videoId();
        lesson.sourceReference="https://www.youtube.com/watch?v="+info.videoId();
        lesson.options=options==null?AuthoringOptions.defaults():options.normalized();lesson.autoContinue=true;lesson.startMs=start; lesson.endMs=end; lesson.status="TRANSCRIPT_REVIEW";lesson.timingStatus="MISSING";
        if(captionFile!=null) {
            var lines=UploadedCaptions.parse(captionFile,start,end);
            lesson.timingEvidence=Map.of("transcriptSource","UPLOADED_EN","segments",captionSegments(lines));
        } else if(preferEnglishCaptions) {
            lesson.timingEvidence=info.englishCaptions()==null ? Map.of("useWhisperX",true) : info.englishCaptions().evidence();
        }
        repository.create(lesson);
        return enqueue(lesson,"PREPARE");
    }
    @Transactional public AuthoringLesson createText(String title,String text,String voice) {return createText(title,text,voice,null);}
    @Transactional public AuthoringLesson createText(String title,String text,String voice,AuthoringOptions options) {
        require(title==null || title.length()<=500,"Title supports a maximum of 500 characters");
        require(text!=null && !text.isBlank() && text.length()<=10000,"Provide 1–10000 text characters");
        requireVoice(voice);
        var lesson=new AuthoringLesson();lesson.id=UUID.randomUUID();lesson.automaticTitle=title==null || title.isBlank();
        lesson.title=lesson.automaticTitle ? automaticTitle(AuthoringText.split(text.strip()).getFirst().text) : title.strip();
        lesson.options=options==null?AuthoringOptions.defaults():options.normalized();lesson.sourceType="TEXT";lesson.ttsVoice=voice;lesson.sourceReference="text:"+lesson.id;
        lesson.status="TRANSCRIPT_REVIEW";lesson.sentences=AuthoringText.split(text.strip());
        require(lesson.sentences.size()<=1000,"Provide up to 1000 sentences");
        repository.create(lesson);return lesson;
    }
    @Transactional public Accepted createAudio(String title,UUID sourceId,long duration,long start,long end) {
        return createAudio(title,sourceId,duration,start,end,null);
    }
    @Transactional public Accepted createAudio(String title,UUID sourceId,long duration,long start,long end,String filename) {return createAudio(title,sourceId,duration,start,end,filename,null);}
    @Transactional public Accepted createAudio(String title,UUID sourceId,long duration,long start,long end,String filename,AuthoringOptions options) {
        require(title==null || title.length()<=500,"Title supports a maximum of 500 characters");
        require(sourceId!=null && start>=0 && end>start && end<=duration && end-start<=600000,"Select up to 10 minutes within the audio source");
        var lesson=new AuthoringLesson();lesson.id=UUID.randomUUID();lesson.automaticTitle=title==null || title.isBlank();
        lesson.title=lesson.automaticTitle ? automaticTitle(filename==null ? "Audio lesson" : filename) : title.strip();
        lesson.options=options==null?AuthoringOptions.defaults():options.normalized();lesson.sourceType="AUDIO";lesson.audioSourceId=sourceId;lesson.sourceReference="audio:"+sourceId;
        lesson.autoContinue=true;lesson.startMs=start;lesson.endMs=end;lesson.status="TRANSCRIPT_REVIEW";
        repository.create(lesson);return enqueue(lesson,"PREPARE");
    }
    @Transactional public AuthoringLesson voice(UUID id,long revision,String voice) {
        var lesson=editable(id,revision);require("TEXT".equals(lesson.sourceType),"Only text lessons have a TTS voice");requireVoice(voice);
        if(voice.equals(lesson.ttsVoice)) return lesson;
        lesson.ttsVoice=voice;invalidateAudio(lesson);lesson.transcriptRevision++;
        repository.save(lesson);return lesson;
    }
    @Transactional public Accepted synthesize(UUID id,long revision) {
        var lesson=editable(id,revision);require("TEXT".equals(lesson.sourceType),"Only text lessons use synthesis");
        require(!lesson.sentences.isEmpty(),"Save text before generating audio");
        require(lesson.sentences.stream().mapToInt(sentence->sentence.text.length()+1).sum()<=10001,"Provide 1–10000 text characters");
        lesson.autoContinue=true;lesson.options=lesson.options.normalized();return enqueue(lesson,"TTS");
    }
    public static String automaticTitle(String source) {
        String title=(source==null ? "Lesson" : source).replaceAll("\\s+"," ").strip();
        if(title.isEmpty()) title="Lesson";
        int points=title.codePointCount(0,title.length());
        return points<=120 ? title : title.substring(0,title.offsetByCodePoints(0,119))+"…";
    }
    private static void requireVoice(String voice) {
        require(voice!=null && voice.matches("(?:af|am|bf|bm)_[a-z]+"),"Choose a supported Kokoro US/UK voice");
    }
    private static void invalidateAudio(AuthoringLesson lesson) {
        lesson.audioObjectKey=null;lesson.endMs=0;lesson.timingStatus="MISSING";lesson.timingEvidence=Map.of();
        lesson.approvedRevision=null;lesson.karaokeReviewedRevision=null;lesson.semanticRevision=null;lesson.activeJobId=null;
        lesson.status="TRANSCRIPT_REVIEW";
        lesson.sentences.forEach(sentence->{sentence.words=List.of();sentence.sourceWords=List.of();
            sentence.coarseStartMs=null;sentence.coarseEndMs=null;sentence.dictationEligible=false;sentence.shadowingEligible=false;});
    }
    public AuthoringLesson get(UUID id) {return find(id,false);}
    private AuthoringLesson find(UUID id,boolean lock) {
        var lesson=repository.find(id,lock).orElseThrow(()->new ResourceNotFoundException("Authoring lesson not found"));
        lesson.alignmentRequired=hasLegacyHidden(lesson) || !("VALID".equals(lesson.timingStatus) || reuseDraftTiming && "DRAFT_VALID".equals(lesson.timingStatus));
        return lesson;
    }
    private AuthoringLesson editable(UUID id,long revision) {
        var lesson=find(id,true);
        if(lesson.publishedAt!=null) throw new StateConflictException("Published lessons are read-only");
        if(lesson.revision!=revision) throw new StateConflictException("Draft changed; refresh before saving");
        return lesson;
    }
    @Transactional public AuthoringLesson text(UUID id,long revision,String text) {
        var lesson=editable(id,revision);
        require("TEXT".equals(lesson.sourceType),"Only text lessons have editable text sources");
        require(text!=null && !text.isBlank() && text.length()<=10000,"Provide 1–10000 text characters");
        var sentences=AuthoringText.split(text.strip());
        var input=new ArrayList<SentenceInput>();
        for(int i=0;i<sentences.size();i++) {
            var sentence=sentences.get(i);
            var old=i<lesson.sentences.size() ? lesson.sentences.get(i) : null;
            input.add(new SentenceInput(old!=null && old.text.equals(sentence.text) ? old.id : null,sentence.text,null));
        }
        return transcript(id,revision,input);
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
            require(item.usage()==null || Set.of("LEARN","WATCH_ONLY","SKIP").contains(item.usage()),"Invalid sentence usage");
            if(item.id()!=null) require(previous.containsKey(item.id()),"Sentence identity does not belong to this draft");
            UUID sentenceId=item.id()==null ? UUID.randomUUID() : item.id();
            require(seen.add(sentenceId),"Duplicate sentence identity");
            var old=previous.get(sentenceId);
            var sentence=new AuthoringSentence(sentenceId,item.text().strip(),old==null ? "LEARN" : old.usage);
            sentence.hidden=false;
            if(old!=null) {
                sentence.coarseStartMs=old.coarseStartMs;sentence.coarseEndMs=old.coarseEndMs;
                sentence.translation=old.translation;sentence.vocabulary=old.vocabulary;sentence.grammar=old.grammar;
                sentence.hints=old.hints;sentence.notes=old.notes;sentence.thoughtGroups=old.thoughtGroups;
                sentence.dictationPriority=old.dictationPriority;sentence.shadowingPriority=old.shadowingPriority;
                sentence.dictationEligible=old.dictationEligible;sentence.shadowingEligible=old.shadowingEligible;
                sentence.dictationSelected=old.dictationSelected;sentence.shadowingSelected=old.shadowingSelected;
                sentence.manualFields=new HashSet<>(old.manualFields);sentence.manualSources=new HashMap<>(old.manualSources);sentence.manualLocales=new HashMap<>(old.manualLocales);sentence.contentStale=true;
                if(old.text.equals(sentence.text)) {sentence.contentKeys=new HashMap<>(old.contentKeys);sentence.cefrSuggestion=old.cefrSuggestion;sentence.contentTypeSuggestion=old.contentTypeSuggestion;}
                sentence.ipaRaw=old.ipaRaw;sentence.ipaDisplayVersion=old.ipaDisplayVersion;sentence.contentWarnings=new ArrayList<>(old.contentWarnings);sentence.exclusions=new HashMap<>(old.exclusions);sentence.cloze=old.cloze;sentence.clozeManual=old.clozeManual;
                sentence.ipa=old.ipa;sentence.ipaAccent=old.ipaAccent;sentence.ipaVersion=old.ipaVersion;sentence.ipaNeedsReview=old.ipaNeedsReview;
                if(old.text.equals(sentence.text)) sentence.ipaReviewIssues=old.ipaReviewIssues;
                if(old.text.equals(sentence.text)) {sentence.words=old.words;sentence.sourceWords=old.sourceWords;sentence.timingSource=old.timingSource;}
            }
            next.add(sentence);length+=item.text().length();
        }
        require(length<=("TEXT".equals(lesson.sourceType) ? 10000 : 200_000),"Transcript exceeds supported size");
        boolean legacyHidden=hasLegacyHidden(lesson);
        if(sameTranscript(lesson.sentences,next)) return lesson;
        boolean spokenTextChanged=!lesson.sentences.stream().map(row->row.text).toList().equals(next.stream().map(row->row.text).toList());
        var observed=lesson.sentences.stream().flatMap(s->s.words.stream())
            .map(w->new AuthoringText.ObservedWord(w.text(),w.startMs(),w.endMs())).toList();
        String oldLexical=lesson.sentences.stream().map(s->AuthoringText.lexicalSequence(s.text)).reduce((a,b)->a+" "+b).orElse("");
        String nextLexical=next.stream().map(s->AuthoringText.lexicalSequence(s.text)).reduce((a,b)->a+" "+b).orElse("");
        if(spokenTextChanged) {
            if(oldLexical.equals(nextLexical) && !observed.isEmpty()) {
                try {AuthoringText.mapTiming(next,observed,lesson.endMs-lesson.startMs);}
                catch(RequestValidationException invalid) {retainCoarseBounds(lesson.sentences,next);lesson.timingStatus="STALE";next.forEach(s->s.words=List.of());}
            } else {
                retainCoarseBounds(lesson.sentences,next);
                next.forEach(sentence->sentence.words=List.of());
                lesson.timingStatus="STALE";
            }
        }
        if(legacyHidden) lesson.timingStatus="STALE";
        next.forEach(s->s.issues=AuthoringText.issues(s));
        lesson.sentences=next;lesson.transcriptRevision++;
        if(Set.of("VALID","DRAFT_VALID").contains(lesson.timingStatus)) {
            try {validateTiming(lesson);} catch(RequestValidationException invalid) {lesson.timingStatus="STALE";}
        }
        if("TEXT".equals(lesson.sourceType) && spokenTextChanged) invalidateAudio(lesson);
        lesson.approvedRevision=null;lesson.karaokeReviewedRevision=null;lesson.semanticRevision=null;
        lesson.activeJobId=null;lesson.status="TRANSCRIPT_REVIEW";
        repository.save(lesson);return lesson;
    }
    private static boolean sameTranscript(List<AuthoringSentence> old,List<AuthoringSentence> next) {
        if(old.size()!=next.size()) return false;
        for(int i=0;i<old.size();i++) if(!old.get(i).id.equals(next.get(i).id)
            || !old.get(i).text.equals(next.get(i).text) || old.get(i).hidden!=next.get(i).hidden || !old.get(i).usage.equals(next.get(i).usage)) return false;
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
    @Transactional public AuthoringLesson timing(UUID id,long revision,UUID sentenceId,List<AuthoringSentence.Word> words) {
        var lesson=editable(id,revision);requireNoActiveJob(lesson);
        require(words!=null && !words.isEmpty() && words.size()<=2000,"Provide observed word intervals");
        var sentence=lesson.sentences.stream().filter(row->row.id.equals(sentenceId)).findFirst()
            .orElseThrow(()->new RequestValidationException("Sentence does not belong to this lesson"));
        require(words.size()==sentence.words.size(),"Manual timing preserves existing word groups");
        for(int i=0;i<words.size();i++) {
            var old=sentence.words.get(i);var word=words.get(i);
            require(word!=null && old.text().equals(word.text()) && old.tokenStart()==word.tokenStart() && old.tokenEnd()==word.tokenEnd()
                && old.charStart()==word.charStart() && old.charEnd()==word.charEnd(),"Timing edit cannot change lexical coverage");
        }
        sentence.words=List.copyOf(words);sentence.timingSource="MANUAL";
        validateTiming(lesson);
        lesson.sentences.forEach(row->row.issues=AuthoringText.issues(row));
        lesson.timingStatus="VALID";lesson.karaokeReviewedRevision=null;lesson.semanticRevision=null;
        repository.save(lesson);return lesson;
    }
    @Transactional public AuthoringLesson contentOptions(UUID id,long revision,AuthoringOptions options) {
        var lesson=editable(id,revision);requireNoActiveJob(lesson);
        require(options!=null,"Provide content options");
        options=options.normalized();
        require("LOCAL".equals(options.pronunciationProvider()),"Content IPA uses the local engine");
        if(options.equals(lesson.options)) return lesson;
        lesson.options=options.normalized();lesson.semanticRevision=null;
        repository.save(lesson);return lesson;
    }
    private void requireReviewed(AuthoringLesson lesson) {

        requireAcceptedTiming(lesson);validateTiming(lesson);
    }
    @Transactional public Accepted generateContent(UUID id,long revision) {
        var lesson=editable(id,revision);requireNoActiveJob(lesson);requireReviewed(lesson);lesson.options=lesson.options.normalized();
        require(AuthoringContent.needed(lesson),"Content is current; review manual fields or change options first");
        lesson.semanticRevision=null;
        return enqueue(lesson,"CONTENT");
    }
    @Transactional public AuthoringLesson saveContent(UUID id,long revision,UUID sentenceId,ContentInput input) {
        var lesson=editable(id,revision);requireNoActiveJob(lesson);
        require(input!=null,"Provide sentence content");
        var sentence=lesson.sentences.stream().filter(s->s.id.equals(sentenceId)).findFirst()
            .orElseThrow(()->new RequestValidationException("Sentence does not belong to this draft"));
        int index=lesson.sentences.indexOf(sentence);
        var fields=new HashSet<String>();
        if((lesson.options.annotations().contains("TRANSLATION") || input.translation()!=null)
            && (!Objects.equals(sentence.translation,input.translation()) || sentence.manualFields.contains("TRANSLATION") && !AuthoringContent.current(lesson,index,"TRANSLATION"))) {
            require(input.translation()!=null && !input.translation().isBlank() && input.translation().length()<=10000,"Provide a translation up to 10000 characters");
            sentence.translation=input.translation();fields.add("TRANSLATION");
        }
        if(!Objects.equals(sentence.ipa,input.ipa()) || sentence.manualFields.contains("IPA") && !AuthoringContent.current(lesson,index,"IPA") || sentence.ipaNeedsReview!=input.ipaNeedsReview()) {
            if(AuthoringContent.features(lesson.options).contains("IPA") || input.ipa()!=null) {
                require(input.ipa()!=null && !input.ipa().isBlank() && input.ipa().length()<=30000,"Provide reference IPA");
                sentence.ipa=input.ipa();sentence.ipaAccent=lesson.options.accent();sentence.ipaVersion="manual";sentence.ipaNeedsReview=input.ipaNeedsReview();fields.add("IPA");
                if(!sentence.ipaNeedsReview) sentence.ipaReviewIssues=List.of();
            }
        }
        AuthoringContent.validateAnnotations(sentence,input.vocabulary(),100);
        AuthoringContent.validateAnnotations(sentence,input.grammar(),30);
        if(!sentence.vocabulary.equals(input.vocabulary()) || sentence.manualFields.contains("LEXICAL") && !AuthoringContent.current(lesson,index,"LEXICAL")) {sentence.vocabulary=List.copyOf(input.vocabulary());fields.add("LEXICAL");}
        if(!sentence.grammar.equals(input.grammar()) || sentence.manualFields.contains("GRAMMAR") && !AuthoringContent.current(lesson,index,"GRAMMAR")) {sentence.grammar=List.copyOf(input.grammar());fields.add("GRAMMAR");}
        boolean speech=!CaptionCueText.isNonSpeech(sentence.text);
        boolean dictation=speech && input.dictationSelected(),shadowing=speech && input.shadowingSelected();
        if(sentence.dictationSelected!=dictation || sentence.manualFields.contains("DICTATION") && !AuthoringContent.current(lesson,index,"DICTATION")) fields.add("DICTATION");
        if(sentence.shadowingSelected!=shadowing || sentence.manualFields.contains("SHADOWING") && !AuthoringContent.current(lesson,index,"SHADOWING")) fields.add("SHADOWING");
        sentence.dictationSelected=dictation;sentence.shadowingSelected=shadowing;
        for(String field:fields) {sentence.manualFields.add(field);sentence.contentKeys.put(field,AuthoringContent.key(lesson,index,field));}
        if(fields.isEmpty()) return lesson;
        sentence.contentStale=false;lesson.semanticRevision=null;lesson.status="CONTENT_REVIEW";
        validateContentBudget(lesson);
        repository.save(lesson);return lesson;
    }
    @Transactional public AuthoringLesson reviewContent(UUID id,long revision) {
        var lesson=editable(id,revision);requireNoActiveJob(lesson);
        require(AuthoringContent.complete(lesson),"Complete enabled content and review stale manual fields first");
        var enabled=AuthoringContent.features(lesson.options);
        for(var sentence:lesson.sentences) {
            if(enabled.contains("TRANSLATION")) require(sentence.translation!=null && !sentence.translation.isBlank(),"Provide every translation");

            if(enabled.contains("IPA")) require(sentence.ipa!=null && !sentence.ipa.isBlank(),"Provide every reference IPA");
            if(enabled.contains("LEXICAL")) AuthoringContent.validateAnnotations(sentence,sentence.vocabulary,100);
            if(enabled.contains("GRAMMAR")) AuthoringContent.validateAnnotations(sentence,sentence.grammar,30);
        }
        validateContentBudget(lesson);
        lesson.semanticRevision=null;lesson.status="CONTENT_REVIEW";repository.save(lesson);return lesson;
    }
    public record ContentInput(String translation,String ipa,boolean ipaNeedsReview,
        List<AuthoringSentence.Annotation> vocabulary,List<AuthoringSentence.Annotation> grammar,
        boolean dictationSelected,boolean shadowingSelected) {}
    private static void validateContentBudget(AuthoringLesson lesson) {
        if(lesson.options.annotations().contains("LEXICAL")) require(java.util.stream.IntStream.range(0,lesson.sentences.size()).filter(i->AuthoringContent.current(lesson,i,"LEXICAL") && !lesson.sentences.get(i).manualFields.contains("LEXICAL")).map(i->lesson.sentences.get(i).vocabulary.size()).sum()<=AuthoringContent.vocabularyLimit(lesson),"Automatic vocabulary budget exceeded");
        if(lesson.options.annotations().contains("GRAMMAR")) require(java.util.stream.IntStream.range(0,lesson.sentences.size()).filter(i->AuthoringContent.current(lesson,i,"GRAMMAR") && !lesson.sentences.get(i).manualFields.contains("GRAMMAR")).map(i->lesson.sentences.get(i).grammar.size()).sum()<=AuthoringContent.grammarLimit(lesson),"Automatic grammar budget exceeded");
    }
    @Transactional public AuthoringLesson saveProfile(UUID id,long revision,
        com.lyreo.lesson.domain.authoring.AuthoringProfile.Cefr cefr,com.lyreo.lesson.domain.authoring.AuthoringProfile.ContentType type) {
        var lesson=editable(id,revision);requireNoActiveJob(lesson);
        require(cefr!=null && type!=null,"Choose a level and content type, or Unknown");
        lesson.profile.cefr=cefr;lesson.profile.contentType=type;lesson.profile.manual=true;
        lesson.profile.key=AuthoringContent.profileKey(lesson);lesson.semanticRevision=null;
        repository.save(lesson);return lesson;
    }
    public record Change(UUID id,String text,ContentInput content,com.lyreo.lesson.domain.authoring.AuthoringCloze.Targets cloze,boolean clozeManual) {}
    public record Changes(long revision,AuthoringOptions options,com.lyreo.lesson.domain.authoring.AuthoringProfile profile,List<Change> sentences,String voice) {}
    @Transactional public AuthoringLesson saveChanges(UUID id,Changes request) {
        require(request!=null,"Provide changes");
        var lesson=editable(id,request.revision());requireNoActiveJob(lesson);
        require(request.sentences()!=null && request.sentences().size()<=1000,"Provide sentences");
        require(request.sentences().stream().noneMatch(Objects::isNull),"Provide valid sentence changes");
        if(!request.sentences().isEmpty()) lesson=transcript(id,lesson.revision,request.sentences().stream().map(c->new SentenceInput(c.id(),c.text(),null)).toList());
        if(request.voice()!=null) lesson=voice(id,lesson.revision,request.voice());
        if(request.options()!=null) lesson=contentOptions(id,lesson.revision,request.options());
        if(request.profile()!=null && request.profile().manual) lesson=saveProfile(id,lesson.revision,request.profile().cefr,request.profile().contentType);
        for(int i=0;i<request.sentences().size();i++) {
            var row=request.sentences().get(i);
            if(row.content()==null) continue;
            lesson=saveContent(id,lesson.revision,lesson.sentences.get(i).id,row.content());
        }
        // Content saves reload the document; apply masks to the final instance so later rows cannot discard them.
        for(int i=0;i<request.sentences().size();i++) {
            var row=request.sentences().get(i);var target=lesson.sentences.get(i);
            if(row.cloze()==null) continue;
            if(row.clozeManual()) {
                if(target.clozeManual && Objects.equals(target.cloze,row.cloze())) continue;
                require(row.cloze().text()!=null && row.cloze().easy()!=null && row.cloze().medium()!=null,"Provide cloze targets");
                require(row.cloze().text().equals(target.text) && AuthoringContent.hash(target.text).equals(row.cloze().textHash()),"Cloze text is stale");
                int count=AuthoringText.tokens(target.text).size();
                require(row.cloze().easy().stream().allMatch(token->token!=null && token>=0 && token<count) && row.cloze().medium().stream().allMatch(token->token!=null && token>=0 && token<count) && row.cloze().medium().containsAll(row.cloze().easy()),"Invalid cloze targets");
                target.cloze=new com.lyreo.lesson.domain.authoring.AuthoringCloze.Targets(row.cloze().easy().stream().distinct().sorted().toList(),row.cloze().medium().stream().distinct().sorted().toList(),"cloze-v2",target.text);target.clozeManual=true;
            } else if(!row.clozeManual()) target.clozeManual=false;
        }
        com.lyreo.lesson.domain.authoring.AuthoringCloze.generateAll(lesson.sentences);repository.save(lesson);
        return lesson;
    }
    @Transactional public AuthoringLesson cancelChain(UUID id,long revision) {
        var lesson=editable(id,revision);lesson.autoContinue=false;
        if(lesson.activeJobId!=null) jobs.cancel(lesson.activeJobId);
        repository.save(lesson);return lesson;
    }
    @Transactional public Accepted run(UUID id,long revision) {
        var lesson=editable(id,revision);requireNoActiveJob(lesson);lesson.autoContinue=true;lesson.options=lesson.options.normalized();
        String operation=lesson.audioObjectKey==null ? (lesson.sourceType.equals("TEXT")?"TTS":"PREPARE")
            : lesson.alignmentRequired?"ALIGN":"CONTENT";
        if(operation.equals("ALIGN")) alignmentSegments(lesson);
        if(operation.equals("CONTENT")) {requireReviewed(lesson);require(AuthoringContent.needed(lesson),"Content is already current");}
        return enqueue(lesson,operation);
    }
    void continueContent(AuthoringLesson lesson) {
        if(lesson.autoContinue && AuthoringContent.needed(lesson)) enqueue(lesson,"CONTENT");
    }
    public List<String> readiness(AuthoringLesson lesson) {
        var errors=new ArrayList<String>();
        if(lesson.jobs.stream().anyMatch(j->Set.of("QUEUED","RUNNING","RETRY_WAIT","CANCEL_REQUESTED").contains(j.status()))) errors.add("JOB_ACTIVE");
        if(lesson.audioObjectKey==null) errors.add("AUDIO_MISSING");
        if(lesson.sentences.isEmpty()) errors.add("TRANSCRIPT_MISSING");
        if(lesson.alignmentRequired || !Set.of("VALID","DRAFT_VALID").contains(lesson.timingStatus)) errors.add("TIMING_STALE");
        try {requireAcceptedTiming(lesson);validateTiming(lesson);} catch(RequestValidationException invalid) {errors.add(invalid.getMessage());}
        if(!AuthoringContent.complete(lesson)) errors.add("CONTENT_INCOMPLETE");
        for(int i=0;i<lesson.sentences.size();i++) {
            var sentence=lesson.sentences.get(i);var enabled=AuthoringContent.features(lesson.options);
            if(enabled.contains("TRANSLATION") && (sentence.translation==null || sentence.translation.isBlank())) errors.add("TRANSLATION_MISSING:"+(i+1));
            if(enabled.contains("IPA") && (sentence.ipa==null || sentence.ipa.isBlank())) errors.add("IPA_MISSING:"+(i+1));
            try {if(enabled.contains("LEXICAL")) AuthoringContent.validateAnnotations(sentence,sentence.vocabulary,100);if(enabled.contains("GRAMMAR")) AuthoringContent.validateAnnotations(sentence,sentence.grammar,30);} catch(RequestValidationException invalid) {errors.add("ANNOTATION_INVALID:"+(i+1));}
        }
        try {validateContentBudget(lesson);} catch(RequestValidationException invalid) {errors.add("NOTE_BUDGET_INVALID");}
        for(String activity:lesson.options.activities()) if(lesson.sentences.stream().noneMatch(row->activity.equals("DICTATION")?row.dictationSelected:row.shadowingSelected)) errors.add(activity+"_EMPTY");
        if(lesson.sentences.stream().anyMatch(row->row.clozeManual && row.cloze!=null && !row.text.equals(row.cloze.text()))) errors.add("CLOZE_STALE");
        return errors;
    }
    public List<Map<String,Object>> diagnostics(UUID id,UUID job) {return repository.diagnostics(id,job);}
    @Transactional public AuthoringLesson publish(UUID id,long revision,UUID actor) {
        var lesson=find(id,true);
        if(lesson.publishedAt!=null) {
            if(revision!=lesson.revision && revision!=lesson.revision-1) throw new StateConflictException("Lesson publication revision does not match");
            return lesson;
        }
        if(lesson.revision!=revision) throw new StateConflictException("Draft changed before publication");
        requireNoActiveJob(lesson);lesson.options=lesson.options.normalized();
        var errors=readiness(lesson);require(errors.isEmpty(),String.join("; ",errors));
        com.lyreo.lesson.domain.authoring.AuthoringCloze.generateAll(lesson.sentences);
        lesson.publishedAt=Instant.now();lesson.publishedBy=actor;lesson.autoContinue=false;lesson.status="PUBLISHED";
        repository.publish(lesson);return lesson;
    }
    @Transactional public AuthoringLesson duplicate(UUID id,long revision) {
        var lesson=find(id,true);if(lesson.revision!=revision) throw new StateConflictException("Lesson changed before duplication");requireNoActiveJob(lesson);
        UUID original=lesson.id;lesson.id=UUID.randomUUID();lesson.title=automaticTitle(lesson.title+" (copy)");lesson.automaticTitle=false;
        lesson.revision=0;lesson.publishedAt=null;lesson.publishedBy=null;lesson.activeJobId=null;lesson.autoContinue=false;lesson.jobs=List.of();
        lesson.approvedRevision=null;lesson.karaokeReviewedRevision=null;lesson.semanticRevision=null;lesson.status="CONTENT_REVIEW";
        lesson.sentences.forEach(row->row.id=UUID.randomUUID());
        repository.create(lesson);repository.save(lesson);repository.copyMetadata(original,lesson.id);return lesson;
    }
    @Transactional public AuthoringLesson approve(UUID id,long revision) {
        var lesson=editable(id,revision);
        require(!lesson.sentences.isEmpty(),"Prepare the transcript first");
        require(!"TEXT".equals(lesson.sourceType) || lesson.audioObjectKey!=null,"Generate current audio before approving text");
        lesson.approvedRevision=lesson.transcriptRevision;
        lesson.status="TIMING_REVIEW";repository.save(lesson);return lesson;
    }
    @Transactional public Accepted align(UUID id,long revision) {
        var lesson=editable(id,revision);
        require(lesson.audioObjectKey!=null,"Generate or prepare current audio first");
        alignmentSegments(lesson);
        return enqueue(lesson,"ALIGN");
    }
    private static boolean hasLegacyHidden(AuthoringLesson lesson) {
        return lesson.publishedAt==null && lesson.sentences.stream().anyMatch(sentence->sentence.hidden);
    }
    void alignPrepared(AuthoringLesson lesson) {
        if(hasLegacyHidden(lesson) || !("VALID".equals(lesson.timingStatus) || reuseDraftTiming && "DRAFT_VALID".equals(lesson.timingStatus)))
            enqueue(lesson,"ALIGN");
        else continueContent(lesson);
    }
    private void requireAcceptedTiming(AuthoringLesson lesson) {
        require(!hasLegacyHidden(lesson),"Align the complete transcript before confirming review");
        require(reuseDraftTiming || "VALID".equals(lesson.timingStatus),"Local alignment is required until draft timing quality has been verified");
    }
    @Transactional public AuthoringLesson karaoke(UUID id,long revision) {
        var lesson=editable(id,revision);
        require(Objects.equals(lesson.approvedRevision,lesson.transcriptRevision),"Approve the current transcript first");
        require(Set.of("VALID","DRAFT_VALID").contains(lesson.timingStatus),"Resolve timing issues before confirming karaoke review");
        requireAcceptedTiming(lesson);
        validateTiming(lesson);
        lesson.karaokeReviewedRevision=lesson.transcriptRevision;
        repository.save(lesson);return lesson;
    }
    @Transactional public Accepted prepare(UUID id,long revision) {
        return prepare(id,revision,false);
    }
    @Transactional public Accepted prepare(UUID id,long revision,boolean useWhisperX) {
        var lesson=editable(id,revision);
        require(!"TEXT".equals(lesson.sourceType),"Use speech synthesis for text lessons");
        require(lesson.sentences.isEmpty(),"Preparation cannot overwrite an edited transcript");
        if(useWhisperX) {
            require("YOUTUBE".equals(lesson.sourceType) && AuthoringSourcePort.CaptionTrack.fromEvidence(lesson.timingEvidence)!=null,
                "The draft has no pending English caption track to replace");
            requireNoActiveJob(lesson);
            lesson.timingEvidence=Map.of("useWhisperX",true);
            lesson.autoContinue=true;
        }
        return enqueue(lesson,"PREPARE");
    }
    static List<Map<String,Object>> captionSegments(List<AuthoringSourcePort.CaptionLine> lines) {
        return lines.stream().map(line -> Map.<String,Object>of("text",line.text(),"start_ms",line.startMs(),"end_ms",line.endMs())).toList();
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
    private void requireNoActiveJob(AuthoringLesson lesson) {
        if(lesson.activeJobId==null) return;
        var job=jobs.findById(lesson.activeJobId);
        if(job.isPresent() && Set.of("QUEUED","RUNNING","RETRY_WAIT","CANCEL_REQUESTED").contains(job.get().status().name()))
            throw new StateConflictException("Wait for the active authoring job before editing timing");
        lesson.activeJobId=null;
    }
    public static void validateTiming(AuthoringLesson lesson) {
        long previousEnd=-1;int index=0,sentenceNumber=0;
        for(var sentence:lesson.sentences) {
            sentenceNumber++;
            if(sentence.words.isEmpty())throw new com.lyreo.lesson.domain.authoring.TimingValidationException("TIMING_MISSING","sentence="+sentenceNumber+", Sentence has no observed timing");
            for(var word:sentence.words) {
                String detail="sentence="+sentenceNumber+", word="+index+", startMs="+word.startMs()+", endMs="+word.endMs()+", durationMs="+(lesson.endMs-lesson.startMs);
                String code=word.startMs()<0 || word.endMs()>lesson.endMs-lesson.startMs ? "TIMING_OUT_OF_BOUNDS"
                    : word.endMs()<=word.startMs() ? "TIMING_ZERO_DURATION" : word.startMs()<previousEnd ? "TIMING_OVERLAP" : null;
                if(code!=null)throw new com.lyreo.lesson.domain.authoring.TimingValidationException(code,detail);
                previousEnd=word.endMs();index++;
            }
        }
    }
    public static List<Map<String,Object>> alignmentSegments(AuthoringLesson lesson) {
        // A sentence can straddle ASR windows; reuse their real anchors while the transcript still matches.
        if(lesson.timingEvidence.get("segments") instanceof List<?> segments && !segments.isEmpty()) {
            var anchored=new ArrayList<Map<String,Object>>();var original=new StringBuilder();long previous=0;
            for(var value:segments) {
                if(!(value instanceof Map<?,?> segment) || !(segment.get("text") instanceof String segmentText)
                    || segmentText.isBlank() || !(segment.get("start_ms") instanceof Number start)
                    || !(segment.get("end_ms") instanceof Number stop)) {anchored.clear();break;}
                long a=start.longValue(),b=stop.longValue();
                if(start.doubleValue()!=a || stop.doubleValue()!=b || a<previous || b<=a || b-a>115_000
                    || b>lesson.endMs-lesson.startMs) {anchored.clear();break;}
                anchored.add(Map.of("text",segmentText,"start_ms",a,"end_ms",b));
                original.append(segmentText).append(' ');previous=b;
            }
            String transcript=String.join(" ",lesson.sentences.stream().map(s->s.text).toList());
            if(!anchored.isEmpty() && original.toString().strip().replaceAll("\\s+"," ")
                .equals(transcript.strip().replaceAll("\\s+"," "))) return List.copyOf(anchored);
        }
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
    public record SentenceInput(UUID id,String text,String usage,Boolean hidden) {
        public SentenceInput(UUID id,String text,String usage) {this(id,text,usage,null);}
    }
    public record Inspect(String videoId,String title,long durationMs,boolean embedAllowed,List<AuthoringRepository.UsedRange> usedRanges,
                          String thumbnailUrl,AuthoringSourcePort.CaptionTrack englishCaptions) {}
    public record Accepted(UUID lessonId,UUID jobId) {}
}
