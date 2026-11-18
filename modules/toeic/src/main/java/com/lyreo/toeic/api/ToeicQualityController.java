package com.lyreo.toeic.api;
import com.lyreo.toeic.application.ToeicQuality;
import com.lyreo.platform.content.QualityReports;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.net.URI;

@RestController
@RequestMapping("/api/v1/admin/toeic")
public class ToeicQualityController {
    private final ToeicQuality quality;
    public ToeicQualityController(ToeicQuality quality) {this.quality=quality;}
    @GetMapping("/{id}/quality") public Map<String,Object> get(@PathVariable UUID id) {return Map.of("report",quality.latest(id).<Object>map(r->r).orElse(Map.of("status","UNCHECKED")));}
    @PostMapping("/{id}/quality") public ResponseEntity<QualityReports.Report> start(@PathVariable UUID id) {return ResponseEntity.accepted().location(URI.create("/api/v1/admin/toeic/"+id+"/quality")).body(quality.start(id));}
}
