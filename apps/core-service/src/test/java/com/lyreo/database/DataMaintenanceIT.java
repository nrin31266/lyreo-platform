package com.lyreo.database;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lyreo.ai.application.execution.*;
import com.lyreo.dataset.application.DataMaintenance;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(classes=DataMaintenanceIT.Config.class)
class DataMaintenanceIT {
    @ServiceConnection static PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:18.6-alpine");
    static {postgres.start();}
    @Configuration(proxyBeanMethods=false) @EnableAutoConfiguration @Import(DataMaintenance.class)
    static class Config {@Bean AiInvocationService ai() {return mock(AiInvocationService.class);}}
    @Autowired DataMaintenance maintenance; @Autowired AiInvocationService ai; @Autowired JdbcTemplate jdbc;
    @TempDir Path directory;

    @BeforeEach void resetProvider() {
        reset(ai);
        jdbc.update("DELETE FROM data_maintenance_work");
        jdbc.update("DELETE FROM data_maintenance_run");
    }

    @Test void cachesSuccessKeepsFailedReservationsAndEnforcesDurableBudgetBeforeCalling() throws Exception {
        Path input=directory.resolve("work.jsonl"),output=directory.resolve("results.json");
        Files.writeString(input,work("first"));
        when(ai.executeMaintenance(anyString(),anyMap(),anyInt(),anyString())).thenReturn(new AiExecutionResult(UUID.randomUUID(),"DEEPSEEK","deepseek-flash",Map.of("structured",Map.of("changes",List.of(),"issues",List.of())),100,20,10));
        maintenance.run(input,output,true,LocalDate.now(),"test-only-key");
        maintenance.run(input,output,true,LocalDate.now(),"test-only-key");
        verify(ai,times(1)).executeMaintenance(anyString(),anyMap(),anyInt(),eq("test-only-key"));
        assertThat(jdbc.queryForObject("SELECT attempts FROM data_maintenance_run WHERE id='library-v2-pilot'",Integer.class)).isEqualTo(1);
        assertThat(Files.readString(output)).contains("SUCCEEDED").doesNotContain("test-only-key");

        Files.writeString(input,work("failed"));
        when(ai.executeMaintenance(anyString(),anyMap(),anyInt(),anyString())).thenThrow(new IllegalStateException("provider unavailable"));
        maintenance.run(input,output,true,LocalDate.now(),"test-only-key");
        maintenance.run(input,output,true,LocalDate.now(),"test-only-key");
        verify(ai,times(2)).executeMaintenance(anyString(),anyMap(),anyInt(),anyString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM data_maintenance_work WHERE run_id='library-v2-pilot' AND status='FAILED' AND reserved_usd>0",Integer.class)).isEqualTo(1);

        jdbc.update("UPDATE data_maintenance_run SET reserved_usd=budget_usd WHERE id='library-v2-pilot'");
        Files.writeString(input,work("over-budget"));
        maintenance.run(input,output,true,LocalDate.now(),"test-only-key");
        verifyNoMoreInteractions(ai);
        assertThat(jdbc.queryForObject("SELECT attempts FROM data_maintenance_run WHERE id='library-v2-pilot'",Integer.class)).isEqualTo(2);
    }

    @Test void resumesOnlyRateLimitedWorkUsingItsExistingReservation() throws Exception {
        Path input=directory.resolve("batch.jsonl"),output=directory.resolve("batch-results.json");
        Files.writeString(input,work("rate-limited"));
        when(ai.executeMaintenance(anyString(),anyMap(),anyInt(),anyString()))
            .thenThrow(new AiExecutionException("Rate limited",true,"AI_HTTP_429"))
            .thenReturn(new AiExecutionResult(UUID.randomUUID(),"DEEPSEEK","deepseek-flash",Map.of("structured",Map.of("changes",List.of(),"issues",List.of())),100,20,10));
        maintenance.run(input,output,true,LocalDate.now(),"test-only-key",true,false);
        assertThat(Files.readString(output)).contains("AI_HTTP_429");
        var reservation = jdbc.queryForObject("SELECT reserved_usd FROM data_maintenance_run WHERE id='library-final'",java.math.BigDecimal.class);
        maintenance.run(input,output,true,LocalDate.now(),"test-only-key",true,false);
        maintenance.run(input,output,true,LocalDate.now(),"test-only-key",true,false);
        assertThat(Files.readString(output)).contains("SUCCEEDED").doesNotContain("AI_HTTP_429");
        assertThat(jdbc.queryForObject("SELECT reserved_usd FROM data_maintenance_run WHERE id='library-final'",java.math.BigDecimal.class)).isEqualByComparingTo(reservation);
        verify(ai,times(2)).executeMaintenance(anyString(),anyMap(),anyInt(),anyString());
    }

    @Test void geminiOnlyProcessesWorkMissingFromTheDeepSeekCheckpoint() throws Exception {
        Path input=directory.resolve("fallback.jsonl"),output=directory.resolve("fallback-results.json");
        var proposal=Map.<String,Object>of("structured",Map.of("changes",List.of(),"issues",List.of()));
        when(ai.executeMaintenance(anyString(),anyMap(),anyInt(),anyString()))
            .thenReturn(new AiExecutionResult(UUID.randomUUID(),"DEEPSEEK","deepseek-flash",proposal,100,20,10));
        when(ai.executeMaintenance(anyString(),anyMap(),anyInt(),anyString(),eq("GEMINI"),eq("gemini-3.5-flash-lite")))
            .thenReturn(new AiExecutionResult(UUID.randomUUID(),"GEMINI","gemini-3.5-flash-lite",proposal,100,20,10));
        Files.writeString(input,work("before-fallback"));
        maintenance.run(input,output,true,LocalDate.now(),"test-only-key",true,false);
        Files.writeString(input,work("before-fallback")+work("after-fallback"));
        maintenance.run(input,output,true,LocalDate.now(),"test-only-key",true,false,
            "GEMINI_FLASH_LITE",new java.math.BigDecimal("0.10"));
        assertThat(Files.readString(output)).contains("after-fallback").doesNotContain("before-fallback");
        verify(ai,times(1)).executeMaintenance(anyString(),anyMap(),eq(4096),anyString(),eq("GEMINI"),eq("gemini-3.5-flash-lite"));
    }

    @Test void resumesWithASmallerQueueUnderTheExplicitCumulativeCeiling() throws Exception {
        Path input=directory.resolve("subset.jsonl"),output=directory.resolve("subset-results.json");
        when(ai.executeMaintenance(anyString(),anyMap(),anyInt(),anyString()))
            .thenReturn(new AiExecutionResult(UUID.randomUUID(),"DEEPSEEK","deepseek-flash",
                Map.of("structured",Map.of("changes",List.of(),"issues",List.of())),100,20,10));
        var ceiling=new java.math.BigDecimal("0.10");
        Files.writeString(input,work("first"));
        maintenance.run(input,output,true,LocalDate.now(),"test-only-key",true,false,"DEEPSEEK",ceiling);
        Files.writeString(input,work("second"));
        maintenance.run(input,output,true,LocalDate.now(),"test-only-key",true,false,"DEEPSEEK",ceiling);
        verify(ai,times(2)).executeMaintenance(anyString(),anyMap(),anyInt(),anyString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM data_maintenance_work WHERE status='SUCCEEDED'",Integer.class)).isEqualTo(2);
    }
    private String work(String id) {
        return """
            {"domain":"grammar","id":"%s","task":"translate","context":{"text":"A source"},"targets":[{"id":"q","fields":{},"allowedFields":["translation_vi"]}]}
            """.formatted(id);
    }
}
