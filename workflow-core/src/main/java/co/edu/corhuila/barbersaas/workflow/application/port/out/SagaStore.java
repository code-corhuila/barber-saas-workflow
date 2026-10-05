package co.edu.corhuila.barbersaas.workflow.application.port.out;

import co.edu.corhuila.barbersaas.workflow.domain.OwnerOnboarding;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence of the saga state (ADR-009, schema workflow). Every write is its own transaction. */
public interface SagaStore {

    Optional<OwnerOnboarding> findById(UUID id);

    Optional<OwnerOnboarding> findByKey(String idempotencyKey);

    /** Raises KeyTaken when another request already started a saga with the same key. */
    void insert(OwnerOnboarding saga);

    void update(OwnerOnboarding saga);

    List<OwnerOnboarding> findRunningSince(Instant updatedBefore);

    class KeyTaken extends RuntimeException {
        public KeyTaken() {
            super("a saga with this Idempotency-Key already exists");
        }
    }
}
