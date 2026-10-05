package co.edu.corhuila.barbersaas.workflow.application.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.corhuila.barbersaas.workflow.application.port.in.OwnerOnboardingUseCases.Barbershop;
import co.edu.corhuila.barbersaas.workflow.application.port.in.OwnerOnboardingUseCases.IdempotencyKeyReused;
import co.edu.corhuila.barbersaas.workflow.application.port.in.OwnerOnboardingUseCases.Owner;
import co.edu.corhuila.barbersaas.workflow.application.port.in.OwnerOnboardingUseCases.Reader;
import co.edu.corhuila.barbersaas.workflow.application.port.in.OwnerOnboardingUseCases.Started;
import co.edu.corhuila.barbersaas.workflow.application.port.out.Participants.BarbershopParticipant;
import co.edu.corhuila.barbersaas.workflow.application.port.out.Participants.OwnerParticipant;
import co.edu.corhuila.barbersaas.workflow.application.port.out.Participants.StepRejected;
import co.edu.corhuila.barbersaas.workflow.application.port.out.Participants.StepUnavailable;
import co.edu.corhuila.barbersaas.workflow.application.port.out.SagaStore;
import co.edu.corhuila.barbersaas.workflow.domain.FailureReason;
import co.edu.corhuila.barbersaas.workflow.domain.OwnerOnboarding;
import co.edu.corhuila.barbersaas.workflow.domain.OwnerOnboardingStep;
import co.edu.corhuila.barbersaas.workflow.domain.SagaStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OwnerOnboardingServiceTest {

    private static final Owner OWNER = new Owner("Andres Rojas", "andres@example.com", "SecurePass123", null);
    private static final Barbershop SHOP = new Barbershop("El Clasico", "Neiva", null, null, null, null);

    private FakeSagas sagas;
    private FakeBarbershops barbershops;
    private FakeOwners owners;
    private List<String> calls;
    private OwnerOnboardingService service;

    @BeforeEach
    void setUp() {
        calls = new ArrayList<>();
        sagas = new FakeSagas(calls);
        barbershops = new FakeBarbershops(calls);
        owners = new FakeOwners(calls);
        service = new OwnerOnboardingService(sagas, barbershops, owners, UUID::randomUUID,
                Clock.fixed(Instant.parse("2026-10-05T12:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void the_happy_path_creates_the_barbershop_then_the_owner_and_saves_after_each_step() {
        Started started = service.start(OWNER, SHOP, "key-00000001");

        OwnerOnboarding saga = started.saga();
        assertTrue(started.created());
        assertEquals(SagaStatus.COMPLETED, saga.status());
        assertEquals(List.of(OwnerOnboardingStep.CREATE_BARBERSHOP, OwnerOnboardingStep.CREATE_OWNER),
                saga.completedSteps());
        assertEquals(barbershops.created, saga.barbershopId());
        assertEquals(owners.created, saga.userId());
        assertEquals(List.of("insert RUNNING", "create-barbershop " + saga.id() + ":create-barbershop",
                "update RUNNING", "create-owner " + saga.id() + ":create-owner", "update COMPLETED"), calls);
    }

    @Test
    void the_owner_step_receives_the_barbershop_just_created() {
        service.start(OWNER, SHOP, "key-00000001");

        assertEquals(barbershops.created, owners.barbershopReceived);
    }

    @Test
    void a_retry_with_the_same_key_returns_the_same_saga_and_runs_no_step() {
        OwnerOnboarding first = service.start(OWNER, SHOP, "key-00000001").saga();
        calls.clear();

        Started retry = service.start(OWNER, SHOP, "key-00000001");

        assertFalse(retry.created());
        assertEquals(first.id(), retry.saga().id());
        assertTrue(calls.isEmpty());
    }

    @Test
    void the_same_key_with_another_body_is_refused() {
        service.start(OWNER, SHOP, "key-00000001");
        Owner other = new Owner("Other", "other@example.com", "SecurePass123", null);

        assertThrows(IdempotencyKeyReused.class, () -> service.start(other, SHOP, "key-00000001"));
    }

    @Test
    void the_password_does_not_change_the_request_hash() {
        Owner otherPassword = new Owner(OWNER.fullName(), OWNER.email(), "AnotherPass456", OWNER.phone());

        assertEquals(OwnerOnboardingService.requestHash(OWNER, SHOP),
                OwnerOnboardingService.requestHash(otherPassword, SHOP));
    }

    @Test
    void an_email_already_registered_removes_the_barbershop_and_compensates() {
        owners.failure = new StepRejected("422 BUSINESS_RULE_VIOLATION");

        OwnerOnboarding saga = service.start(OWNER, SHOP, "key-00000001").saga();

        assertEquals(SagaStatus.COMPENSATED, saga.status());
        assertEquals(OwnerOnboardingStep.CREATE_OWNER, saga.failedStep());
        assertEquals(FailureReason.EMAIL_ALREADY_REGISTERED, saga.failureReason());
        assertEquals(List.of(OwnerOnboardingStep.CREATE_BARBERSHOP), saga.completedSteps());
        assertEquals(List.of(barbershops.created), barbershops.deleted);
        assertNull(saga.userId());
    }

    @Test
    void identity_not_answering_also_compensates() {
        owners.failure = new StepUnavailable("timeout after 3 attempts");

        OwnerOnboarding saga = service.start(OWNER, SHOP, "key-00000001").saga();

        assertEquals(SagaStatus.COMPENSATED, saga.status());
        assertEquals(FailureReason.STEP_UNAVAILABLE, saga.failureReason());
        assertEquals(1, barbershops.deleted.size());
    }

    @Test
    void a_failed_compensation_leaves_the_saga_failed_for_a_person() {
        owners.failure = new StepRejected("422");
        barbershops.deleteFailure = new StepUnavailable("barbershop-api down");

        OwnerOnboarding saga = service.start(OWNER, SHOP, "key-00000001").saga();

        assertEquals(SagaStatus.FAILED, saga.status());
        assertEquals(OwnerOnboardingStep.CREATE_OWNER, saga.failedStep());
        assertTrue(saga.failureDetail().contains("barbershop-api down"));
    }

    @Test
    void a_failed_first_step_undoes_nothing_and_never_calls_identity() {
        barbershops.createFailure = new StepUnavailable("barbershop-api down");

        OwnerOnboarding saga = service.start(OWNER, SHOP, "key-00000001").saga();

        assertEquals(SagaStatus.COMPENSATED, saga.status());
        assertEquals(OwnerOnboardingStep.CREATE_BARBERSHOP, saga.failedStep());
        assertTrue(saga.completedSteps().isEmpty());
        assertTrue(barbershops.deleted.isEmpty());
        assertNull(owners.barbershopReceived);
    }

    @Test
    void an_owner_reads_only_the_saga_of_their_barbershop() {
        OwnerOnboarding saga = service.start(OWNER, SHOP, "key-00000001").saga();

        assertTrue(service.find(saga.id(), new Reader(false, saga.barbershopId())).isPresent());
        assertTrue(service.find(saga.id(), new Reader(false, UUID.randomUUID())).isEmpty());
        assertTrue(service.find(saga.id(), new Reader(true, null)).isPresent());
        assertTrue(service.find(UUID.randomUUID(), new Reader(true, null)).isEmpty());
    }

    @Test
    void a_saga_interrupted_after_the_barbershop_is_compensated() {
        OwnerOnboarding saga = OwnerOnboarding.start(UUID.randomUUID(), "key-00000009", "hash",
                Instant.parse("2026-10-05T11:00:00Z"));
        UUID barbershop = UUID.randomUUID();
        saga.barbershopCreated(barbershop, Instant.parse("2026-10-05T11:00:01Z"));
        sagas.byId.put(saga.id(), saga);

        assertEquals(1, service.recoverInterrupted(Duration.ofMinutes(5)));

        assertEquals(SagaStatus.COMPENSATED, saga.status());
        assertEquals(FailureReason.INTERRUPTED, saga.failureReason());
        assertEquals(List.of(barbershop), barbershops.deleted);
    }

    @Test
    void a_saga_interrupted_before_the_barbershop_was_recorded_is_left_to_a_person() {
        OwnerOnboarding saga = OwnerOnboarding.start(UUID.randomUUID(), "key-00000009", "hash",
                Instant.parse("2026-10-05T11:00:00Z"));
        sagas.byId.put(saga.id(), saga);

        service.recoverInterrupted(Duration.ofMinutes(5));

        assertEquals(SagaStatus.FAILED, saga.status());
        assertEquals(OwnerOnboardingStep.CREATE_BARBERSHOP, saga.failedStep());
        assertTrue(barbershops.deleted.isEmpty());
    }

    @Test
    void a_recent_running_saga_is_not_touched() {
        OwnerOnboarding saga = OwnerOnboarding.start(UUID.randomUUID(), "key-00000009", "hash",
                Instant.parse("2026-10-05T11:59:00Z"));
        sagas.byId.put(saga.id(), saga);

        assertEquals(0, service.recoverInterrupted(Duration.ofMinutes(5)));
        assertEquals(SagaStatus.RUNNING, saga.status());
    }

    private static final class FakeSagas implements SagaStore {
        final Map<UUID, OwnerOnboarding> byId = new HashMap<>();
        private final List<String> calls;

        FakeSagas(List<String> calls) {
            this.calls = calls;
        }

        public Optional<OwnerOnboarding> findById(UUID id) {
            return Optional.ofNullable(byId.get(id));
        }

        public Optional<OwnerOnboarding> findByKey(String key) {
            return byId.values().stream().filter(s -> s.idempotencyKey().equals(key)).findFirst();
        }

        public void insert(OwnerOnboarding saga) {
            calls.add("insert " + saga.status());
            byId.put(saga.id(), saga);
        }

        public void update(OwnerOnboarding saga) {
            calls.add("update " + saga.status());
        }

        public List<OwnerOnboarding> findRunningSince(Instant updatedBefore) {
            return byId.values().stream()
                    .filter(s -> s.status() == SagaStatus.RUNNING && s.updatedAt().isBefore(updatedBefore)).toList();
        }
    }

    private static final class FakeBarbershops implements BarbershopParticipant {
        final UUID created = UUID.randomUUID();
        final List<UUID> deleted = new ArrayList<>();
        RuntimeException createFailure;
        RuntimeException deleteFailure;
        private final List<String> calls;

        FakeBarbershops(List<String> calls) {
            this.calls = calls;
        }

        public UUID createBarbershop(String stepKey, Barbershop barbershop) {
            if (createFailure != null) {
                throw createFailure;
            }
            calls.add("create-barbershop " + stepKey);
            return created;
        }

        public void deleteBarbershop(UUID barbershopId) {
            if (deleteFailure != null) {
                throw deleteFailure;
            }
            deleted.add(barbershopId);
        }
    }

    private static final class FakeOwners implements OwnerParticipant {
        final UUID created = UUID.randomUUID();
        UUID barbershopReceived;
        RuntimeException failure;
        private final List<String> calls;

        FakeOwners(List<String> calls) {
            this.calls = calls;
        }

        public UUID createOwner(String stepKey, Owner owner, UUID barbershopId) {
            barbershopReceived = barbershopId;
            if (failure != null) {
                throw failure;
            }
            calls.add("create-owner " + stepKey);
            return created;
        }
    }
}
