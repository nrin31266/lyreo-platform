package com.lyreo.platform.bootstrap;

import java.nio.file.Files;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** Applies the same Flyway schema without starting HTTP, workers or AI integrations. */
public final class DatabaseSetup {
    public static void main(String[] args) throws Exception {
        if (args.length != 0 && (args.length != 2 || !args[0].equals("--seed-dev")))
            throw new IllegalArgumentException("Usage: DatabaseSetup [--seed-dev <reference SQL file>]");
        var datasource = new DriverManagerDataSource(
            required("DATABASE_URL"), required("DATABASE_USERNAME"), required("DATABASE_PASSWORD"));
        apply(datasource, args.length == 0 ? null : Path.of(args[1]));
    }

    static void apply(DataSource datasource, Path seed) {
        if (seed != null && !Files.isRegularFile(seed))
            throw new IllegalArgumentException("Development reference SQL file is missing");
        Flyway.configure().dataSource(datasource).validateMigrationNaming(true).load().migrate();
        System.out.println("Database migrations are current.");
        if (seed != null) {
            new ResourceDatabasePopulator(new FileSystemResource(seed)).execute(datasource);
            System.out.println("Development reference data is current in the configured database.");
        }
    }

    private static String required(String key) {
        var value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing " + key);
        return value;
    }
}
