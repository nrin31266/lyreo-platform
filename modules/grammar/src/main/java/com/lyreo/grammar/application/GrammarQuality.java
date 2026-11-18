package com.lyreo.grammar.application;
import com.lyreo.platform.content.QualityReports;
import com.lyreo.platform.jobs.application.*;
import com.lyreo.platform.jobs.domain.BackgroundJob;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class GrammarQuality implements JobHandler {
    private static final UUID ALL=new UUID(0,0);
    private final GrammarCatalogEditor editor;private final QualityReports reports;
    public GrammarQuality(GrammarCatalogEditor editor,QualityReports reports) {this.editor=editor;this.reports=reports;}
    public String jobType() {return "GRAMMAR_QUALITY";}
    private Object content(String kind,UUID id) {return kind.equals("all")?"grammar-all":editor.get(kind,id);}
    public Optional<QualityReports.Report> latest(String kind,UUID id) {return reports.latest("grammar-"+kind,id,reports.fingerprint(content(kind,id)));}
    public QualityReports.Report start(String kind,UUID id) {
        long revision=kind.equals("all")?0:editor.get(kind,id).revision();
        return reports.start("grammar-"+kind,id,revision,reports.fingerprint(content(kind,id)),jobType(),Map.of("kind",kind));
    }
    public UUID all() {return ALL;}
    public void execute(BackgroundJob job,JobExecutionContext context) {
        var report=reports.get(job.ownerReferenceId());String kind=report.context().get("kind").toString();
        context.progress("CHECKING_GRAMMAR",10);context.throwIfCancellationRequested();
        List<QualityReports.Issue> issues;
        if(kind.equals("all")) issues=editor.review(context::throwIfCancellationRequested).stream().map(i->new QualityReports.Issue(i.code(),i.severity(),i.title(),i.message(),i.questionId()==null?"/grammar/"+i.kind()+"/"+i.id():"/grammar/questions/"+i.questionId())).toList();
        else issues=editor.check(kind,report.targetId(),context::throwIfCancellationRequested).stream().map(i->new QualityReports.Issue(i.code(),i.severity(),i.number()==0?"Catalog":"Question "+i.number(),i.message(),i.questionId()==null?null:"/grammar/questions/"+i.questionId())).toList();
        context.throwIfCancellationRequested();reports.complete(report.id(),job.id(),job.leaseOwner(),issues);context.progress("CHECKED",100);
    }
}
