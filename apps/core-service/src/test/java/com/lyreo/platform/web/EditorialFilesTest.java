package com.lyreo.platform.web;

import static org.assertj.core.api.Assertions.*;
import com.lyreo.platform.content.EditorialFiles;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class EditorialFilesTest {
    final EditorialFiles files=new EditorialFiles(new ObjectMapper());
    @Test void spreadsheetExportsNeutralizeFormulasAndPreserveQuoting() {
        var csv=files.csv(List.of("value"),List.of(List.of("=1+1"),List.of(" \t@SUM(A1)"),List.of("He said \"hi\"")));
        assertThat(csv).contains("\"'=1+1\"","\"' \t@SUM(A1)\"","\"He said \"\"hi\"\"\"");
    }
}
