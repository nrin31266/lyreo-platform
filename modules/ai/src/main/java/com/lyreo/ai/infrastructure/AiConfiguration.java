package com.lyreo.ai.infrastructure;

import com.lyreo.ai.application.AiAdminRepository;
import com.lyreo.ai.application.AiAdminService;
import com.lyreo.ai.application.AiInvocationService;
import com.lyreo.ai.application.AiRouter;
import com.lyreo.ai.application.AiRoutingSnapshotService;
import com.lyreo.platform.security.SecretCipher;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class AiConfiguration {

    @Bean
    SecretCipher secretCipher(@Value("${lyreo.security.master-encryption-key}") String key) {
        return SecretCipher.fromBase64(key);
    }

    @Bean
    JdbcAiRouteRepository aiRouteRepository(NamedParameterJdbcTemplate jdbc, SecretCipher cipher) {
        return new JdbcAiRouteRepository(jdbc, cipher);
    }

    @Bean
    FastApiAiExecutionGateway aiExecutionGateway(
        WebClient.Builder builder,
        @Value("${lyreo.ai-service.base-url}") String baseUrl,
        @Value("${lyreo.ai-service.internal-token}") String internalToken,
        @Value("${lyreo.ai-service.timeout-seconds:300}") long timeoutSeconds
    ) {
        return new FastApiAiExecutionGateway(
            builder.baseUrl(baseUrl).build(),
            Duration.ofSeconds(timeoutSeconds),
            internalToken
        );
    }

    @Bean
    AiRouter aiRouter(JdbcAiRouteRepository repository) {
        return new AiRouter(repository);
    }

    @Bean
    AiRoutingSnapshotService aiRoutingSnapshotService(JdbcAiRouteRepository repository) {
        return new AiRoutingSnapshotService(repository);
    }

    @Bean
    JdbcAiInvocationAuditRepository aiInvocationAuditRepository(NamedParameterJdbcTemplate jdbc) {
        return new JdbcAiInvocationAuditRepository(jdbc);
    }

    @Bean
    AiAdminRepository aiAdminRepository(NamedParameterJdbcTemplate jdbc) {
        return new JdbcAiAdminRepository(jdbc);
    }

    @Bean
    AiAdminService aiAdminService(AiAdminRepository repository, SecretCipher cipher) {
        return new AiAdminService(repository, cipher);
    }

    @Bean
    @org.springframework.context.annotation.Profile("dev")
    DevAiBootstrap devAiBootstrap(AiAdminService admin) { return new DevAiBootstrap(admin); }

    @Bean
    AiInvocationService aiInvocationService(
        AiRouter router,
        JdbcAiRouteRepository repository,
        FastApiAiExecutionGateway gateway,
        JdbcAiInvocationAuditRepository audit
    ) {
        return new AiInvocationService(router, repository, gateway, audit);
    }
}
