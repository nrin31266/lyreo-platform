package com.lyreo.platform.content;

import com.lyreo.contracts.errors.RequestValidationException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Small authoring files only; dataset packages keep their independent importer. */
@Service
public class EditorialFiles {
    private final ObjectMapper json;
    public EditorialFiles(ObjectMapper json) {this.json=json;}
    public record Row<T>(int row,T content,List<String> errors) {}
    public record Preview<T>(List<Row<T>> rows,int valid,int invalid) {}
    public <T> Preview<T> preview(byte[] bytes,String filename,Function<Map<String,Object>,T> decode) {
        var records=records(bytes,filename);var rows=new ArrayList<Row<T>>();int valid=0;
        for(int i=0;i<records.size();i++) {
            try {if(records.get(i)==null) throw new RequestValidationException("Each record must be an object");rows.add(new Row<>(i+1,decode.apply(records.get(i)),List.of()));valid++;}
            catch(RequestValidationException | com.lyreo.contracts.errors.StateConflictException invalid) {rows.add(new Row<>(i+1,null,List.of(invalid.getMessage())));}
            catch(IllegalArgumentException | tools.jackson.core.JacksonException invalid) {rows.add(new Row<>(i+1,null,List.of("Invalid field types or content schema")));}
        }
        return new Preview<>(rows,valid,rows.size()-valid);
    }
    public List<Map<String,Object>> records(byte[] bytes,String filename) {
        if(bytes==null || bytes.length==0 || bytes.length>10*1024*1024) throw new RequestValidationException("Choose an authoring file up to 10 MiB");
        try {
            List<Map<String,Object>> records;
            String text=StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString().replaceFirst("^\\uFEFF","");
            if(filename!=null && filename.toLowerCase(Locale.ROOT).endsWith(".csv")) records=csv(text);
            else {
                var root=json.readTree(text);
                if(root.path("version").asInt()!=1 || !root.path("records").isArray()) throw new RequestValidationException("Use JSON {version:1, records:[...]}");
                records=json.convertValue(root.path("records"),new TypeReference<>(){});
            }
            if(records.isEmpty() || records.size()>5000) throw new RequestValidationException("Import between 1 and 5000 rows");
            return records;
        } catch(tools.jackson.core.JacksonException | IllegalArgumentException | java.nio.charset.CharacterCodingException invalid) {throw new RequestValidationException("Invalid UTF-8 CSV or versioned JSON file");}
    }
    private static List<Map<String,Object>> csv(String text) {
        var cells=new ArrayList<List<String>>();var row=new ArrayList<String>();var cell=new StringBuilder();boolean quoted=false,closed=false;
        for(int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if(quoted) {
                if(c=='"') {if(i+1<text.length() && text.charAt(i+1)=='"') {cell.append('"');i++;} else {quoted=false;closed=true;}}
                else cell.append(c);
            } else if(c==',' || c=='\n' || c=='\r') {
                row.add(cell.toString());cell.setLength(0);closed=false;
                if(c!=',') {if(c=='\r' && i+1<text.length() && text.charAt(i+1)=='\n') i++;if(row.stream().anyMatch(value->!value.isEmpty())) cells.add(row);row=new ArrayList<>();}
            } else if(c=='"' && cell.isEmpty() && !closed) quoted=true;
            else {if(closed || c=='"') throw new RequestValidationException("Malformed CSV quoting");cell.append(c);}
            if(cells.size()>5001) throw new RequestValidationException("At most 5000 CSV rows");
        }
        if(quoted) throw new RequestValidationException("Unclosed CSV quotation");
        row.add(cell.toString());if(row.stream().anyMatch(value->!value.isEmpty())) cells.add(row);
        if(cells.size()<2) throw new RequestValidationException("CSV needs a header and data rows");
        var headers=cells.getFirst();if(headers.stream().anyMatch(String::isBlank) || new HashSet<>(headers).size()!=headers.size()) throw new RequestValidationException("CSV headers must be nonempty and unique");
        var result=new ArrayList<Map<String,Object>>();
        for(var values:cells.subList(1,cells.size())) {
            if(values.size()!=headers.size()) throw new RequestValidationException("CSV row "+(result.size()+1)+" has a different number of columns");
            var mapped=new LinkedHashMap<String,Object>();for(int i=0;i<headers.size();i++) mapped.put(headers.get(i),values.get(i));result.add(mapped);
        }
        return result;
    }
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
    public <T> T decode(Map<String,Object> value,Class<T> type) {return json.convertValue(value,type);}
    public static String text(Map<String,Object> row,String field) {return Objects.toString(row.get(field),"");}
}
