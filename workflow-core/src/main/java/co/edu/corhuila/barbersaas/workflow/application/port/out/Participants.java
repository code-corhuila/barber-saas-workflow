package co.edu.corhuila.barbersaas.workflow.application.port.out;

import co.edu.corhuila.barbersaas.workflow.application.port.in.OwnerOnboardingUseCases.Barbershop;
import co.edu.corhuila.barbersaas.workflow.application.port.in.OwnerOnboardingUseCases.Owner;
import java.util.UUID;

/**
 * The services the saga calls, each with its do and, if it has one, its undo. The adapter sends
 * the step key as Idempotency-Key, the workflow's service token and the correlation id (DEC-WF-04).
 */
public final class Participants {

    private Participants() { }

    public interface BarbershopParticipant {
        UUID createBarbershop(String stepKey, Barbershop barbershop);

        /** Idempotent: a barbershop that no longer exists counts as removed. */
        void deleteBarbershop(UUID barbershopId);
    }

    public interface OwnerParticipant {
        UUID createOwner(String stepKey, Owner owner, UUID barbershopId);
    }

    /** The participant answered and refused (a 4xx business rule): retrying would not help. */
    public static class StepRejected extends RuntimeException {
        public StepRejected(String detail) {
            super(detail);
        }
    }

    /** The participant did not answer, or answered 429/5xx, after the bounded retries. */
    public static class StepUnavailable extends RuntimeException {
        public StepUnavailable(String detail) {
            super(detail);
        }
    }
}
