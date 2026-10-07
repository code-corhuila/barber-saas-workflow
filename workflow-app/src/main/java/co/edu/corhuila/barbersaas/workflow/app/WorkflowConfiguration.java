package co.edu.corhuila.barbersaas.workflow.app;

import co.edu.corhuila.barbersaas.workflow.adapter.in.http.AuthFilter;
import co.edu.corhuila.barbersaas.workflow.adapter.in.http.CorrelationFilter;
import co.edu.corhuila.barbersaas.workflow.adapter.in.http.Rs256Verifier;
import co.edu.corhuila.barbersaas.workflow.adapter.out.http.HttpParticipants;
import co.edu.corhuila.barbersaas.workflow.adapter.out.http.ParticipantClient;
import co.edu.corhuila.barbersaas.workflow.adapter.out.persistence.InMemorySagaStore;
import co.edu.corhuila.barbersaas.workflow.adapter.out.persistence.JdbcSagaStore;
import co.edu.corhuila.barbersaas.workflow.application.port.in.OwnerOnboardingUseCases;
import co.edu.corhuila.barbersaas.workflow.application.port.out.SagaStore;
import co.edu.corhuila.barbersaas.workflow.application.usecase.OwnerOnboardingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Composition root: the only place that knows every concrete type, and where every limit is
 * declared explicitly (norm 5.3.10) instead of hidden in defaults.
 */
@Configuration
public class WorkflowConfiguration {

    private static final Logger log = LoggerFactory.getLogger(WorkflowConfiguration.class);

    /** The workflow schema as workflow_app (ADR-009), or in memory when DATABASE_URL is empty. */
    @Bean
    SagaStore sagaStore(@Value("${workflow.database.url:}") String url,
                        @Value("${workflow.database.user:}") String user,
                        @Value("${workflow.database.password:}") String password,
                        @Value("${workflow.database.pool-max:5}") int poolMax,
                        @Value("${workflow.database.statement-timeout-ms:5000}") int statementTimeoutMs) {
        if (url.isBlank()) {
            log.warn("DATABASE_URL is empty: sagas are kept in memory and do not survive a restart");
            return new InMemorySagaStore();
        }
        HikariConfig pool = new HikariConfig();
        pool.setJdbcUrl(url);
        pool.setUsername(user);                                       // workflow_app, never the administrator
        pool.setPassword(password);
        pool.setMaximumPoolSize(poolMax);
        pool.setConnectionTimeout(Duration.ofSeconds(5).toMillis());
        pool.setMaxLifetime(Duration.ofMinutes(30).toMillis());
        pool.setConnectionInitSql("SET statement_timeout = " + statementTimeoutMs);
        return new JdbcSagaStore(new JdbcTemplate(new HikariDataSource(pool)));
    }

    /** SERVICE_TOKEN: this service's own token, sub barber-saas-workflow (07-api/authentication.md). */
    @Bean
    HttpParticipants participants(ObjectMapper json,
                                  @Value("${SERVICE_TOKEN:}") String serviceToken,
                                  @Value("${BARBERSHOP_API_URL:http://barbershop-api:8080}") String barbershopUrl,
                                  @Value("${PLATFORM_ADMIN_API_URL:http://platform-admin-api:8080}") String platformAdminUrl,
                                  @Value("${IDENTITY_AUTH_API_URL:http://identity-auth-api:8080}") String identityUrl,
                                  @Value("${PARTICIPANT_TIMEOUT_MS:3000}") long timeoutMs,
                                  @Value("${PARTICIPANT_MAX_ATTEMPTS:3}") int maxAttempts,
                                  @Value("${PARTICIPANT_BACKOFF_MS:200}") long backoffMs) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        ParticipantClient client = new ParticipantClient(http, serviceToken, Duration.ofMillis(timeoutMs), maxAttempts,
                Duration.ofMillis(backoffMs));
        return new HttpParticipants(client, json, barbershopUrl, platformAdminUrl, identityUrl);
    }

    @Bean
    OwnerOnboardingUseCases ownerOnboarding(SagaStore sagas, HttpParticipants participants) {
        return new OwnerOnboardingService(sagas, participants, participants, participants, UUID::randomUUID,
                Clock.systemUTC());
    }

    /** A saga left RUNNING by a restart is ended at startup, compensating what it can (DEC-WF-03). */
    @Bean
    ApplicationRunner recoverInterruptedSagas(OwnerOnboardingUseCases onboarding,
                                              @Value("${SAGA_RECOVERY_AGE_SECONDS:120}") long ageSeconds) {
        return args -> {
            int recovered = onboarding.recoverInterrupted(Duration.ofSeconds(ageSeconds));
            if (recovered > 0) {
                log.warn("ended {} saga(s) interrupted by a restart", recovered);
            }
        };
    }

    /** JWT_PUBLIC_KEY: the PEM every service validates with; one line with literal \n escapes is accepted. */
    @Bean
    Rs256Verifier tokenVerifier(@Value("${JWT_PUBLIC_KEY:}") String pem) {
        return new Rs256Verifier(pem.replace("\\n", "\n"));
    }

    @Bean
    FilterRegistrationBean<CorrelationFilter> correlationFilter() {
        FilterRegistrationBean<CorrelationFilter> bean = new FilterRegistrationBean<>(new CorrelationFilter());
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return bean;
    }

    @Bean
    FilterRegistrationBean<AuthFilter> authFilter(Rs256Verifier verifier, ObjectMapper json) {
        FilterRegistrationBean<AuthFilter> bean = new FilterRegistrationBean<>(new AuthFilter(verifier, json));
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return bean;
    }
}
