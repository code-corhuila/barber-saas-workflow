package co.edu.corhuila.barbersaas.workflow.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OwnerOnboardingTest {

    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");

    @Test
    void the_step_key_is_the_saga_id_and_the_step_name() {
        UUID id = UUID.randomUUID();
        OwnerOnboarding saga = OwnerOnboarding.start(id, "key-00000001", "hash", NOW);

        assertEquals(id + ":create-barbershop", saga.stepKey(OwnerOnboardingStep.CREATE_BARBERSHOP));
        assertEquals(id + ":create-owner", saga.stepKey(OwnerOnboardingStep.CREATE_OWNER));
    }

    @Test
    void the_owner_step_cannot_complete_before_the_barbershop_step() {
        OwnerOnboarding saga = OwnerOnboarding.start(UUID.randomUUID(), "key-00000001", "hash", NOW);

        assertThrows(IllegalStateException.class, () -> saga.completed(UUID.randomUUID(), NOW));
    }

    @Test
    void a_finished_saga_does_not_change_again() {
        OwnerOnboarding saga = OwnerOnboarding.start(UUID.randomUUID(), "key-00000001", "hash", NOW);
        saga.barbershopCreated(UUID.randomUUID(), NOW);
        saga.completed(UUID.randomUUID(), NOW);

        assertThrows(IllegalStateException.class, () -> saga.compensated(OwnerOnboardingStep.CREATE_OWNER,
                FailureReason.STEP_UNAVAILABLE, null, NOW));
    }

    @Test
    void step_names_round_trip_with_the_contract() {
        for (OwnerOnboardingStep step : OwnerOnboardingStep.values()) {
            assertEquals(step, OwnerOnboardingStep.fromWireName(step.wireName()));
        }
    }
}
