package com.lyreo.platform.bootstrap;

import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** Readiness checks local binaries only; no cloud/provider calls. */
@Component("libraryRuntime")
public class LibraryRuntimeHealth implements HealthIndicator {
    private Instant expires=Instant.MIN;
    private Health cached;
    @Override public synchronized Health health() {
        if(cached!=null && Instant.now().isBefore(expires)) return cached;
        boolean media=command("ffprobe","-version") && command("ffmpeg","-version");
        cached=(media?Health.up():Health.down()).withDetail("mediaTools",media).build();
        expires=Instant.now().plusSeconds(30);return cached;
    }
    private static boolean command(String... args) {
        Process process=null;
        try {
            process=new ProcessBuilder(args).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            return process.waitFor(5,TimeUnit.SECONDS) && process.exitValue()==0;
        } catch(java.io.IOException failure) {return false;}
          catch(InterruptedException interrupted) {Thread.currentThread().interrupt();return false;}
        finally {if(process!=null && process.isAlive()) process.destroyForcibly();}
    }
}
