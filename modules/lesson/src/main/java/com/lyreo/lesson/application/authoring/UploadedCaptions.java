package com.lyreo.lesson.application.authoring;

import com.lyreo.contracts.errors.RequestValidationException;
import com.lyreo.lesson.application.port.AuthoringSourcePort.CaptionLine;
import com.lyreo.lesson.domain.authoring.AuthoringText;
import com.lyreo.lesson.domain.authoring.CaptionCueText;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.web.util.HtmlUtils;

public final class UploadedCaptions {
    private static final String CLOCK = "(?:[0-9]{2,3}:)?[0-5][0-9]:[0-5][0-9][.,][0-9]{3}";
    private static final Pattern TIMING = Pattern.compile("^("+CLOCK+")\\s+-->\\s+("+CLOCK+")(?:\\s+.*)?$");
    private static final Pattern MARKUP = Pattern.compile("<[^>\\n]*>");

    private UploadedCaptions() {}

    public record File(String format, String text) {}

    static List<CaptionLine> parse(File file, long clipStart, long clipEnd) {
        if (!("srt".equals(file.format()) || "vtt".equals(file.format())) || file.text()==null
            || file.text().length()>262_144 || file.text().getBytes(StandardCharsets.UTF_8).length>262_144)
            throw invalid("Choose an SRT or VTT file up to 256 KiB");
        String text=file.text().replaceFirst("^\\uFEFF", "").replace("\r\n", "\n").replace('\r','\n').strip();
        if (text.isBlank()) throw invalid("The uploaded file has no usable English captions in the selected range");
        boolean vtt="vtt".equals(file.format());
        var blocks=text.split("\\n(?:[ \\t]*\\n)+");
        if (vtt && !blocks[0].lines().findFirst().orElse("").matches("WEBVTT(?:[ \\t].*)?"))
            throw invalid("The file is missing its WEBVTT header");
        var rows=new ArrayList<CaptionLine>();
        long previousEnd=-1;
        int totalLength=0;
        for (int i=vtt ? 1 : 0; i<blocks.length; i++) {
            var block=blocks[i].lines().toList();
            String first=block.getFirst().strip();
            if (vtt && (first.matches("NOTE(?:[ \\t].*)?") || first.equals("STYLE") || first.equals("REGION"))) continue;
            int index=first.contains("-->") ? 0 : 1;
            if (block.size()<=index+1 || (!vtt && index==1 && !first.matches("[0-9]+")))
                throw invalid("A caption cue is missing its clock or text");
            var timing=TIMING.matcher(block.get(index).strip());
            if (!timing.matches()) throw invalid("An uploaded caption has an invalid timestamp");
            long start=millis(timing.group(1)), end=millis(timing.group(2));
            if (start>=end) throw invalid("An uploaded caption ends before it starts");
            if (end<=clipStart || start>=clipEnd) continue;
            String line=CaptionCueText.speech(HtmlUtils.htmlUnescape(
                MARKUP.matcher(String.join(" ",block.subList(index+1,block.size()))).replaceAll("")));
            if (AuthoringText.tokens(line).isEmpty()) continue;
            if (start<clipStart || end>clipEnd) throw invalid("The selected range cuts through an uploaded caption line");
            totalLength+=line.length();
            if (start<previousEnd) throw invalid("Uploaded caption lines overlap");
            if (end-start>115_000 || line.length()>10_000 || rows.size()==1000 || totalLength>200_000)
                throw invalid("Uploaded captions exceed the supported transcript length");
            rows.add(new CaptionLine(line,start-clipStart,end-clipStart));
            previousEnd=end;
        }
        if (rows.isEmpty()) throw invalid("The uploaded file has no usable English captions in the selected range");
        return List.copyOf(rows);
    }

    private static long millis(String value) {
        String[] parts=value.split("[:.,]");
        long result=0;
        for (int i=0; i<parts.length-1; i++) result=result*60+Integer.parseInt(parts[i]);
        return result*1000+Integer.parseInt(parts[parts.length-1]);
    }

    private static RequestValidationException invalid(String message) { return new RequestValidationException(message); }
}
