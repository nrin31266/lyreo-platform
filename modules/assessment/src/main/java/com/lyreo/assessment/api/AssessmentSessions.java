package com.lyreo.assessment.api;

import com.lyreo.assessment.application.port.SessionRepository;
import com.lyreo.assessment.domain.QuestionContent;
import com.lyreo.contracts.errors.*;
import com.lyreo.contracts.grammar.GrammarQuestionAnsweredEvent;
import com.lyreo.contracts.grammar.GrammarSessionCompletedEvent;
import com.lyreo.contracts.toeic.ToeicAttemptCompletedEvent;
import com.lyreo.platform.config.application.OperationIdempotencyService;
import com.lyreo.platform.content.AssetStore;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
@NamedInterface(value="api",propagate=false)
public class AssessmentSessions {
    private static final String START_OPERATION = "assessment.session.start";

    private final SessionRepository repository;
    private final ObjectMapper json;
    private final ApplicationEventPublisher events;
    private final AssetStore assets;
    private final OperationIdempotencyService idempotency;

    public AssessmentSessions(SessionRepository repository,ObjectMapper json,ApplicationEventPublisher events,AssetStore assets) {
        this(repository,json,events,assets,(OperationIdempotencyService) null);
    }

    public AssessmentSessions(SessionRepository repository,ObjectMapper json,ApplicationEventPublisher events,AssetStore assets,OperationIdempotencyService idempotency) {
        this.repository=repository;
        this.json=json;
        this.events=events;
        this.assets=assets;
        this.idempotency=idempotency;
    }

    @Autowired
    public AssessmentSessions(SessionRepository repository,ObjectMapper json,ApplicationEventPublisher events,AssetStore assets,ObjectProvider<OperationIdempotencyService> idempotencyProvider) {
        this(repository,json,events,assets,idempotencyProvider.getIfAvailable());
    }

    @NamedInterface(value="api",propagate=false)
    public record Item(UUID id,UUID itemId,UUID publicationId,int part,int number,UUID topicId,UUID subtopicId,UUID groupId,QuestionContent content,Integer gapNumber) {}
    @NamedInterface(value="api",propagate=false)
    public record Group(UUID id,int part,List<String> documents,List<QuestionContent.Media> media,String transcriptEn,String translationVi) {
        public Group(UUID id,int part,List<String> documents,List<QuestionContent.Media> media) {this(id,part,documents,media,null,null);}
    }
    @NamedInterface(value="api",propagate=false)
    public record GroupView(UUID id,int part,List<String> documents,List<QuestionContent.Media> media) {}
    @NamedInterface(value="api",propagate=false)
    public record Snapshot(String title,UUID publicationId,List<Item> items,List<Group> groups) {}
    @NamedInterface(value="api",propagate=false)
    public record QuestionView(UUID id,UUID itemId,int part,int number,UUID groupId,String text,List<QuestionContent.Option> options,List<QuestionContent.Media> media,Integer gapNumber) {}
    @NamedInterface(value="api",propagate=false)
    public record Feedback(UUID id,UUID itemId,int part,int number,String answer,String correctAnswer,boolean correct,String explanationVi,String translationVi,UUID groupId,QuestionContent content,Integer gapNumber) {}
    @NamedInterface(value="api",propagate=false)
    public record Result(int listeningCorrect,int listeningTotal,int readingCorrect,int readingTotal,int correct,int total,Integer listeningScaledScore,Integer readingScaledScore,List<Feedback> questions,List<Group> groups) {}
    @NamedInterface(value="api",propagate=false)
    public record View(UUID id,String domain,UUID catalogId,String mode,long revision,String status,Instant createdAt,Instant expiresAt,String title,List<QuestionView> questions,List<GroupView> groups,Map<UUID,String> answers,Result result) {}
    @NamedInterface(value="api",propagate=false)
    public record History(UUID id,UUID catalogId,String title,String mode,String status,Instant createdAt,Instant submittedAt,Result result) {}

    public View startWithIdempotency(UUID learnerId,String domain,UUID catalogId,String mode,Snapshot snapshot,String idempotencyKey) {
        String fingerprint = domain + ":" + catalogId + ":" + mode + ":" + json.writeValueAsString(snapshot);
        return startWithIdempotency(learnerId,domain,catalogId,mode,snapshot,idempotencyKey,fingerprint);
    }

    public View startWithIdempotency(UUID learnerId,String domain,UUID catalogId,String mode,Snapshot snapshot,String idempotencyKey,String requestFingerprint) {
        if(idempotencyKey == null || idempotencyKey.isBlank()) {
            return start(learnerId,domain,catalogId,mode,snapshot);
        }
        String normalizedKey = idempotencyKey.trim();
        if(normalizedKey.length() > 128) {
            throw new RequestValidationException("Idempotency-Key must be at most 128 characters");
        }
        if(idempotency == null) {
            throw new IllegalStateException("OperationIdempotencyService is not configured");
        }
        validateStart(domain, snapshot);
        return idempotency.execute(
            learnerId,
            START_OPERATION,
            normalizedKey,
            requestFingerprint,
            View.class,
            () -> start(learnerId,domain,catalogId,mode,snapshot)
        );
    }

    @Transactional
    public View start(UUID learnerId,String domain,UUID catalogId,String mode,Snapshot snapshot) {
        validateStart(domain, snapshot);
        UUID id=UUID.randomUUID();
        repository.insert(id,learnerId,domain,catalogId,mode,json.writeValueAsString(snapshot));
        return get(learnerId,id);
    }

    private void validateStart(String domain, Snapshot snapshot) {
        if(domain==null || !Set.of("grammar","toeic").contains(domain) || snapshot==null || snapshot.title()==null || snapshot.groups()==null || snapshot.items()==null || snapshot.items().isEmpty() || snapshot.items().size()>200) throw new RequestValidationException("Choose between 1 and 200 questions");
        var ids=new HashSet<UUID>();var groups=new HashSet<UUID>();
        for(var group:snapshot.groups()) {
            if(group==null || group.id()==null || !groups.add(group.id()) || group.documents()==null || group.media()==null || group.documents().stream().anyMatch(Objects::isNull)
                || group.media().stream().anyMatch(media -> media==null || media.assetId()==null || media.role()==null || !Set.of("audio","image").contains(media.role())))
                throw new RequestValidationException("Invalid session group");
        }
        for(var item:snapshot.items()) {
            if(item==null || item.id()==null || item.itemId()==null || !ids.add(item.id()) || item.groupId()!=null && !groups.contains(item.groupId())) throw new RequestValidationException("Invalid session questions");
            AssessmentLibrary.validate(item.content(),true);
        }
    }

    public View get(UUID learnerId,UUID id) {return view(owned(learnerId,id,false));}

    @Transactional
    public View save(UUID learnerId,UUID id,long revision,Map<UUID,String> answers) {
        var row=owned(learnerId,id,true);open(row,revision);
        var snapshot=snapshot(row);validateAnswers(snapshot,answers);
        repository.answers(id,revision,json.writeValueAsString(answers));
        return get(learnerId,id);
    }

    @Transactional
    public View submit(UUID learnerId,UUID id,long revision) {
        var row=owned(learnerId,id,true);
        if(row.submittedAt()!=null) return view(row);
        open(row,revision);
        var snapshot=snapshot(row);Map<UUID,String> answers=answers(row);
        var feedback=new ArrayList<Feedback>();int listening=0,listeningTotal=0,reading=0,readingTotal=0,correctTotal=0;
        for(var item:snapshot.items()) {
            String answer=answers.get(item.id());boolean correct=Objects.equals(item.content().correctOption(),answer);
            if(correct) correctTotal++;
            if(item.part()>0 && item.part()<=4) {listeningTotal++;if(correct) listening++;}
            if(item.part()>=5) {readingTotal++;if(correct) reading++;}
            feedback.add(new Feedback(item.id(),item.itemId(),item.part(),item.number(),answer,item.content().correctOption(),correct,item.content().explanationVi(),item.content().translationVi(),item.groupId(),item.content(),item.gapNumber()));
        }
        var result=new Result(listening,listeningTotal,reading,readingTotal,correctTotal,feedback.size(),null,null,feedback,snapshot.groups());
        repository.submit(id,json.writeValueAsString(result));
        Instant now=Instant.now();
        if("toeic".equals(row.domain())) {
            events.publishEvent(new ToeicAttemptCompletedEvent(learnerId,id,row.catalogId(),row.mode(),listening,listeningTotal,reading,readingTotal,null,null,now));
        } else if("grammar".equals(row.domain())) {
            for(int index=0;index<snapshot.items().size();index++) {
                var item=snapshot.items().get(index);var score=feedback.get(index);
                UUID answerId=UUID.nameUUIDFromBytes((id+":"+item.id()).getBytes(StandardCharsets.UTF_8));
                events.publishEvent(new GrammarQuestionAnsweredEvent(learnerId,answerId,item.itemId(),item.topicId(),item.subtopicId(),item.content().difficultyLevel()==null?1:item.content().difficultyLevel(),score.correct(),now));
            }
            events.publishEvent(new GrammarSessionCompletedEvent(row.id(),learnerId,row.catalogId(),row.mode(),result.correct(),result.total(),now));
        }
        return get(learnerId,id);
    }

    public List<History> history(UUID learnerId,String domain,int page,int size) {
        return history(learnerId,domain,null,null,page,size);
    }

    public List<History> history(UUID learnerId,String domain,String status,UUID catalogId,int page,int size) {
        if(domain==null || !Set.of("grammar","toeic").contains(domain) || page<0 || page>100000 || size<1 || size>100) throw new RequestValidationException("Invalid history page");
        String normalizedStatus = (status == null || status.isBlank()) ? null : status.trim().toUpperCase(Locale.ROOT);
        if(normalizedStatus != null && !Set.of("OPEN","SUBMITTED","EXPIRED").contains(normalizedStatus)) {
            throw new RequestValidationException("Invalid session status filter");
        }
        return repository.history(learnerId,domain,normalizedStatus,catalogId,page,size).stream().map(row->new History(row.id(),row.catalogId(),snapshot(row).title(),row.mode(),status(row),row.createdAt(),row.submittedAt(),result(row))).toList();
    }

    public AssetStore.Asset media(UUID learnerId,UUID sessionId,UUID assetId) {
        var snapshot=snapshot(owned(learnerId,sessionId,false));
        boolean present=snapshot.items().stream().flatMap(item->item.content().media().stream())
            .anyMatch(media->media!=null && media.assetId()!=null && media.assetId().equals(assetId))
            || snapshot.groups().stream().flatMap(group->group.media().stream())
            .anyMatch(media->media!=null && media.assetId()!=null && media.assetId().equals(assetId));
        if(!present) throw new ResourceNotFoundException("Session media not found");
        return assets.get(assetId);
    }
    private void validateAnswers(Snapshot snapshot,Map<UUID,String> answers) {
        if(answers==null || answers.size()>snapshot.items().size()) throw new RequestValidationException("Invalid answers");
        var items=new HashMap<UUID,Item>();snapshot.items().forEach(item->items.put(item.id(),item));
        for(var answer:answers.entrySet()) if(!items.containsKey(answer.getKey()) || answer.getValue()==null || items.get(answer.getKey()).content().options().stream().noneMatch(option->option.key().equals(answer.getValue()))) throw new RequestValidationException("Answers must belong to this session and match a question option");
    }
    private void open(SessionRepository.Row row,long revision) {
        if(row.submittedAt()!=null) throw new StateConflictException("Session is already submitted");
        if(!row.expiresAt().isAfter(Instant.now())) throw new SessionExpiredException("Session expired after 24 hours",row.expiresAt());
        if(row.revision()!=revision) throw new StaleRevisionException("Session changed; reload before continuing",row.revision());
    }
    private SessionRepository.Row owned(UUID learnerId,UUID id,boolean lock) {return repository.find(id,learnerId,lock).orElseThrow(()->new ResourceNotFoundException("Session not found"));}
    private Snapshot snapshot(SessionRepository.Row row) {return json.readValue(row.snapshot(),Snapshot.class);}
    private Map<UUID,String> answers(SessionRepository.Row row) {return json.readValue(row.answers(),new TypeReference<>(){});}
    private Result result(SessionRepository.Row row) {return row.result()==null?null:json.readValue(row.result(),Result.class);}
    private String status(SessionRepository.Row row) {return row.submittedAt()!=null?"SUBMITTED":row.expiresAt().isAfter(Instant.now())?"OPEN":"EXPIRED";}
    private View view(SessionRepository.Row row) {
        var snapshot=snapshot(row);
        var questions = snapshot.items().stream().map(item -> {
            var content = item.content();
            boolean audioOnly = item.part() == 1 || item.part() == 2;
            var options = audioOnly
                ? content.options().stream().map(option -> new QuestionContent.Option(option.key(), "")).toList()
                : content.options();
            return new QuestionView(item.id(), item.itemId(), item.part(), item.number(), item.groupId(),
                audioOnly ? null : content.title(), options, content.media(), item.gapNumber());
        }).toList();
        return new View(row.id(),row.domain(),row.catalogId(),row.mode(),row.revision(),status(row),row.createdAt(),row.expiresAt(),snapshot.title(),questions,snapshot.groups().stream().map(group->new GroupView(group.id(),group.part(),group.documents(),group.media())).toList(),answers(row),result(row));
    }
}
