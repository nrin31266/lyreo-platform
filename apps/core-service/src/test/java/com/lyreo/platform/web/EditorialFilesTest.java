package com.lyreo.platform.web;

import static org.assertj.core.api.Assertions.*;
import com.lyreo.platform.content.EditorialFiles;
import com.lyreo.contracts.errors.RequestValidationException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class EditorialFilesTest {
    final EditorialFiles files=new EditorialFiles(new ObjectMapper());
    byte[] bytes(String text) {return text.getBytes(StandardCharsets.UTF_8);}
    @Test void csvSupportsUnicodeQuotedCommasAndEmbeddedNewlines() {
        var rows=files.records(bytes("headword,definition\r\nbook,\"Pages, bound\nTập hợp\"\r\n"),"words.csv");
        assertThat(rows).hasSize(1);assertThat(rows.getFirst().get("definition")).isEqualTo("Pages, bound\nTập hợp");
        assertThatThrownBy(()->files.records(bytes("a,a\n1,2"),"file.csv")).isInstanceOf(RequestValidationException.class);
        assertThatThrownBy(()->files.records(bytes("a,b\n\"missing,quote"),"file.csv")).isInstanceOf(RequestValidationException.class);
        assertThatThrownBy(()->files.records(new byte[]{(byte)0xff},"file.csv")).isInstanceOf(RequestValidationException.class);
    }
    @Test void spreadsheetExportsNeutralizeFormulasAndPreserveQuoting() {
        var csv=files.csv(List.of("value"),List.of(List.of("=1+1"),List.of(" \t@SUM(A1)"),List.of("He said \"hi\"")));
        assertThat(csv).contains("\"'=1+1\"","\"' \t@SUM(A1)\"","\"He said \"\"hi\"\"\"");
    }
    @Test void versionAndLimitsAreCheckedBeforePreviewAndRowsRetainErrors() {
        assertThatThrownBy(()->files.records(bytes("{\"version\":2,\"records\":[{}]}"),"file.json")).isInstanceOf(RequestValidationException.class);
        assertThatThrownBy(()->files.records(new byte[10*1024*1024+1],"file.json")).isInstanceOf(RequestValidationException.class);
        var rows=new ArrayList<Map<String,Object>>();for(int i=0;i<5001;i++) rows.add(Map.of("title","word"));
        assertThatThrownBy(()->files.records(bytes(files.json(rows)),"file.json")).isInstanceOf(RequestValidationException.class);
        var preview=files.preview(bytes("{\"version\":1,\"records\":[{\"title\":\"Word\"},null,{}]}"),"file.json",row->{if(!row.containsKey("title")) throw new RequestValidationException("Title required");return row;});
        assertThat(preview.valid()).isEqualTo(1);assertThat(preview.invalid()).isEqualTo(2);
        assertThat(preview.rows().get(2).row()).isEqualTo(3);assertThat(preview.rows().get(2).errors()).containsExactly("Title required");
    }
}
