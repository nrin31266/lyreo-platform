package com.lyreo.database;

import static org.assertj.core.api.Assertions.*;
import com.lyreo.platform.content.DocumentStore;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(classes=ContentRevisionLockIT.Config.class)
class ContentRevisionLockIT {
    @ServiceConnection static PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:18.6-alpine");
    static {postgres.start();}
    @Configuration(proxyBeanMethods=false) @EnableAutoConfiguration @Import(DocumentStore.class)
    static class Config {}
    @Autowired DocumentStore documents; @Autowired TransactionTemplate transactions;

    @Test void aContextualEditLocksEvenAnUneditedSourceUntilItsTransactionCompletes() throws Exception {
        UUID id=UUID.randomUUID();var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var threads=Executors.newVirtualThreadPerTaskExecutor()) {
            var contextual=threads.submit(()->transactions.execute(status->{
                documents.requireRevision("toeic",id,0);locked.countDown();
                try {if(!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("Test release timed out");}
                catch(InterruptedException interrupted) {Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}
                return null;
            }));
            assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
            var started=new CountDownLatch(1);
            var concurrent=threads.submit(()->{started.countDown();return documents.save("toeic",id,0,Map.of("title","Concurrent passage edit"),"other-admin",Map.class);});
            assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();
            try {assertThatThrownBy(()->concurrent.get(250,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);}
            finally {release.countDown();}
            contextual.get(5,TimeUnit.SECONDS);
            assertThat(concurrent.get(5,TimeUnit.SECONDS).revision()).isEqualTo(1);
            assertThatThrownBy(()->transactions.execute(status->{documents.requireRevision("toeic",id,0);return null;}))
                .isInstanceOf(com.lyreo.contracts.errors.StateConflictException.class);
        } finally {release.countDown();}
    }
}
