package com.lyreo.grammar.api;
import com.lyreo.grammar.application.GrammarQuality;
import com.lyreo.platform.content.QualityReports;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.net.URI;

@RestController
@RequestMapping("/api/v1/admin/grammar")
public class GrammarQualityController {
    private final GrammarQuality quality;
    public GrammarQualityController(GrammarQuality quality) {this.quality=quality;}
    @GetMapping("/quality") public Map<String,Object> all() {return Map.of("report",quality.latest("all",quality.all()).<Object>map(r->r).orElse(Map.of("status","UNCHECKED")));}
    @PostMapping("/quality") public ResponseEntity<QualityReports.Report> startAll() {return accepted(quality.start("all",quality.all()),"/api/v1/admin/grammar/quality");}
    @GetMapping("/{kind}/{id}/quality") public Map<String,Object> get(@PathVariable String kind,@PathVariable UUID id) {return Map.of("report",quality.latest(kind,id).<Object>map(r->r).orElse(Map.of("status","UNCHECKED")));}
    @PostMapping("/{kind}/{id}/quality") public ResponseEntity<QualityReports.Report> start(@PathVariable String kind,@PathVariable UUID id) {return accepted(quality.start(kind,id),"/api/v1/admin/grammar/"+kind+"/"+id+"/quality");}
    private ResponseEntity<QualityReports.Report> accepted(QualityReports.Report report,String location) {return ResponseEntity.accepted().location(URI.create(location)).body(report);}
}
