package com.lyreo.dataset.application;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
class DataMaintenanceTest {
    final DataMaintenance.Work work=new DataMaintenance.Work("toeic","group","translate",Map.of("answer","B"),List.of(new DataMaintenance.Target("question",Map.of("correct_option","B"),List.of("translation_vi"))));
    @Test void acceptsOnlyScopedLanguageFieldsAndNeverAnswers() {
        DataMaintenance.validate(work,new DataMaintenance.Proposal(List.of(new DataMaintenance.Change("question","translation_vi","Nghĩa","From the passage")),List.of()));
        for(String field:List.of("correct_option","options","transcript_en")) assertThatThrownBy(()->DataMaintenance.validate(work,new DataMaintenance.Proposal(List.of(new DataMaintenance.Change("question",field,"A","Source")),List.of()))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->DataMaintenance.validate(work,new DataMaintenance.Proposal(List.of(new DataMaintenance.Change("another","translation_vi","Nghĩa","Source")),List.of()))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void reservesBothInputAndMaximumOutputAtPeakPricing() {
        assertThat(DataMaintenance.reservation(20000)).isEqualByComparingTo("0.01644480");
        assertThat(DataMaintenance.reservation(20000).multiply(java.math.BigDecimal.valueOf(91))).isLessThan(DataMaintenance.BUDGET);
        assertThat(DataMaintenance.reservation(20000).multiply(java.math.BigDecimal.valueOf(92))).isGreaterThan(DataMaintenance.BUDGET);
    }
    @Test void canonicalInputIgnoresMapOrderButPreservesQuestionAndDocumentOrder() {
        var first=new LinkedHashMap<String,Object>();first.put("z",List.of(Map.of("b",2,"a",1)));first.put("a","source");
        var second=new LinkedHashMap<String,Object>();second.put("a","source");second.put("z",List.of(Map.of("a",1,"b",2)));
        var json=new tools.jackson.databind.ObjectMapper();
        assertThat(json.writeValueAsString(DataMaintenance.canonical(first))).isEqualTo(json.writeValueAsString(DataMaintenance.canonical(second)));
        assertThat(DataMaintenance.canonical(List.of("second","first"))).isEqualTo(List.of("second","first"));
    }
}
