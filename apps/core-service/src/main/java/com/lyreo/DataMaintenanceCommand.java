package com.lyreo;

import com.lyreo.dataset.application.DataMaintenance;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Arrays;
import org.springframework.boot.builder.SpringApplicationBuilder;

/** Local operator entry point; there is no paid maintenance HTTP endpoint. */
public final class DataMaintenanceCommand {
    public static void main(String[] args) throws Exception {
        String input=System.getenv("DATA_MAINTENANCE_INPUT"),output=System.getenv("DATA_MAINTENANCE_OUTPUT");
        if(input==null || output==null) throw new IllegalArgumentException("Set maintenance input/output paths");
        boolean execute=Arrays.asList(args).contains("--execute");
        String model=System.getenv().getOrDefault("DATA_MAINTENANCE_MODEL","DEEPSEEK");
        String confirmed=System.getenv("DATA_MAINTENANCE_PRICING_CONFIRMED_ON");
        String budget=System.getenv("DATA_MAINTENANCE_BUDGET_USD");
        String credential=System.getenv(DataMaintenance.credentialVariable(model));
        try(var app=new SpringApplicationBuilder(LyreoApplication.class).properties("server.port=0","spring.devtools.restart.enabled=false","lyreo.jobs.scheduler-enabled=false").run("--server.port=0","--lyreo.jobs.scheduler-enabled=false")) {
            app.getBean(DataMaintenance.class).run(Path.of(input),Path.of(output),execute,confirmed==null?null:LocalDate.parse(confirmed),credential,Arrays.asList(args).contains("--batch"),Arrays.asList(args).contains("--sample"),model,budget==null?null:new BigDecimal(budget));
        }
    }
}
