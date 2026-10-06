package co.edu.corhuila.barbersaas.workflow.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.corhuila.barbersaas.workflow.application.port.out.SagaStore.KeyTaken;
import co.edu.corhuila.barbersaas.workflow.domain.FailureReason;
import co.edu.corhuila.barbersaas.workflow.domain.OwnerOnboarding;
import co.edu.corhuila.barbersaas.workflow.domain.OwnerOnboardingStep;
import co.edu.corhuila.barbersaas.workflow.domain.SagaStatus;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Against a real workflow schema (db/ applied), as workflow_app. Runs only when
 * TEST_DATABASE_URL is set, e.g. against the instance of barber-saas-infra-postgres.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class JdbcSagaStoreTest {

    private JdbcSagaStore store;

    @BeforeEach
    void connect() {
        DriverManagerDataSource db = new DriverManagerDataSource(System.getenv("TEST_DATABASE_URL"),
                System.getenv("TEST_DATABASE_USER"), System.getenv("TEST_DATABASE_PASSWORD"));
        store = new JdbcSagaStore(new JdbcTemplate(db));
    }

    @Test
    void a_saga_is_written_after_each_step_and_read_back_whole() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        OwnerOnboarding saga = OwnerOnboarding.start(UUID.randomUUID(), "it-" + UUID.randomUUID(), "hash", now);
        store.insert(saga);
        UUID barbershop = UUID.randomUUID();
        saga.barbershopCreated(barbershop, now);
        store.update(saga);
        saga.compensated(OwnerOnboardingStep.CREATE_OWNER, FailureReason.EMAIL_ALREADY_REGISTERED, "422", now);
        store.update(saga);

        OwnerOnboarding read = store.findByKey(saga.idempotencyKey()).orElseThrow();

        assertEquals(SagaStatus.COMPENSATED, read.status());
        assertEquals(List.of(OwnerOnboardingStep.CREATE_BARBERSHOP), read.completedSteps());
        assertEquals(OwnerOnboardingStep.CREATE_OWNER, read.failedStep());
        assertEquals(FailureReason.EMAIL_ALREADY_REGISTERED, read.failureReason());
        assertEquals(barbershop, read.barbershopId());
        assertEquals(now, read.createdAt());
    }

    @Test
    void the_same_key_cannot_start_two_sagas() {
        String key = "it-" + UUID.randomUUID();
        store.insert(OwnerOnboarding.start(UUID.randomUUID(), key, "hash", Instant.now()));

        assertThrows(KeyTaken.class,
                () -> store.insert(OwnerOnboarding.start(UUID.randomUUID(), key, "hash", Instant.now())));
    }

    @Test
    void running_sagas_are_found_by_age() {
        OwnerOnboarding old = OwnerOnboarding.start(UUID.randomUUID(), "it-" + UUID.randomUUID(), "hash",
                Instant.now().minus(1, ChronoUnit.HOURS));
        store.insert(old);

        assertTrue(store.findRunningSince(Instant.now().minus(5, ChronoUnit.MINUTES)).stream()
                .anyMatch(s -> s.id().equals(old.id())));
    }
}
