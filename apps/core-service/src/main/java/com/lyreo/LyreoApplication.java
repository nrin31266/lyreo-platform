package com.lyreo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class LyreoApplication {
    public static void main(String[] args) {
        SpringApplication.run(LyreoApplication.class, args);
    }
}
