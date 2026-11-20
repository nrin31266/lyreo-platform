package com.lyreo.toeic.application;
import com.lyreo.platform.content.QualityReports;
import com.lyreo.platform.jobs.application.*;
import com.lyreo.platform.jobs.domain.BackgroundJob;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class ToeicQuality implements JobHandler {
    private final ToeicEditor editor;private final QualityReports reports;
    public ToeicQuality(ToeicEditor editor,QualityReports reports) {this.editor=editor;this.reports=reports;}
    public String jobType() {return "TOEIC_QUALITY";}
    public Optional<QualityReports.Report> latest(UUID id) {return reports.latest("toeic",id,reports.fingerprint(editor.get(id)));}
    public QualityReports.Report start(UUID id) {var document=editor.get(id);return reports.start("toeic",id,document.revision(),reports.fingerprint(document),jobType(),Map.of());}
    public void execute(BackgroundJob job,JobExecutionContext context) {
        var report=reports.get(job.ownerReferenceId());context.progress("CHECKING_TOEIC",10);context.throwIfCancellationRequested();
        var issues=editor.readiness(editor.get(report.targetId()).draft()).stream().map(i->new QualityReports.Issue(i.code(),i.severity(),i.location(),i.message(),"/toeic/"+report.targetId()+"?tab=content&location="+java.net.URLEncoder.encode(i.location(),java.nio.charset.StandardCharsets.UTF_8))).toList();
        context.throwIfCancellationRequested();reports.complete(report.id(),job.id(),job.leaseOwner(),issues);context.progress("CHECKED",100);
    }
}
