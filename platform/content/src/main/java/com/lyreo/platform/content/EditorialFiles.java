package com.lyreo.platform.content;

import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Individual editorial exports, separate from dataset packages. */
@Service
public class EditorialFiles {
    private final ObjectMapper json;
    public EditorialFiles(ObjectMapper json) {this.json=json;}
    public String json(List<?> records) {return json.writeValueAsString(Map.of("version",1,"records",records));}
    public String csv(List<String> headers,List<List<String>> rows) {
        var lines=new ArrayList<String>();lines.add(headers.stream().map(EditorialFiles::cell).collect(java.util.stream.Collectors.joining(",")));
        for(var row:rows) lines.add(row.stream().map(EditorialFiles::cell).collect(java.util.stream.Collectors.joining(",")));
        return String.join("\r\n",lines)+"\r\n";
    }
    private static String cell(String value) {
        String safe=Objects.toString(value,"");
        if(safe.matches("(?s)^[\\s\\p{Cc}]*[=+@-].*") || safe.startsWith("\t") || safe.startsWith("\r")) safe="'"+safe;
        return "\""+safe.replace("\"","\"\"")+"\"";
    }
}
