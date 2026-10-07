package co.edu.corhuila.barbersaas.workflow.application.port.in;

import co.edu.corhuila.barbersaas.workflow.domain.OwnerOnboarding;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/** What the workflow offers for owner onboarding (workflow-service.yaml). */
public interface OwnerOnboardingUseCases {

    /** Sent to identity-auth in create-owner; the password is never stored (DEC-WF-03). */
    record Owner(String fullName, String email, String password, String phone) { }

    /** Sent to barbershop in create-barbershop. */
    record Barbershop(String name, String city, String address, String phone, Double latitude, Double longitude) { }

    /** {@code created} is false on a retry with the same Idempotency-Key: 200 instead of 201, no step runs again. */
    record Started(OwnerOnboarding saga, boolean created) { }

    /** Who reads a saga: SUPER_ADMIN reads any; an owner only the one that created their barbershop. */
    record Reader(boolean superAdmin, UUID barbershopId) { }

    /** Runs the saga to its end and returns it in its final status (DEC-WF-02), on the plan the owner picked (DEC-WF-05). */
    Started start(Owner owner, Barbershop barbershop, UUID planId, String idempotencyKey);

    /** Empty when it does not exist or the reader may not see it: 404 either way. */
    Optional<OwnerOnboarding> find(UUID id, Reader reader);

    /** Ends the sagas a restart left RUNNING for longer than {@code age}; returns how many (DEC-WF-03). */
    int recoverInterrupted(Duration age);

    /** The same Idempotency-Key with a different body: 422. */
    class IdempotencyKeyReused extends RuntimeException {
        public IdempotencyKeyReused() {
            super("The Idempotency-Key was already used with a different request");
        }
    }
}
