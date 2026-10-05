package co.edu.corhuila.barbersaas.workflow.adapter.out.persistence;

import co.edu.corhuila.barbersaas.workflow.application.port.out.SagaStore;
import co.edu.corhuila.barbersaas.workflow.domain.OwnerOnboarding;
import co.edu.corhuila.barbersaas.workflow.domain.SagaStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Used when DATABASE_URL is empty, to try the HTTP contract without a database. It does not
 * survive a restart, so it never runs in qa or main (ADR-009, annex E).
 */
public class InMemorySagaStore implements SagaStore {

    private final Map<UUID, OwnerOnboarding> sagas = new ConcurrentHashMap<>();

    @Override
    public Optional<OwnerOnboarding> findById(UUID id) {
        return Optional.ofNullable(sagas.get(id));
    }

    @Override
    public Optional<OwnerOnboarding> findByKey(String idempotencyKey) {
        return sagas.values().stream().filter(s -> s.idempotencyKey().equals(idempotencyKey)).findFirst();
    }

    @Override
    public synchronized void insert(OwnerOnboarding saga) {
        if (findByKey(saga.idempotencyKey()).isPresent()) {
            throw new KeyTaken();
        }
        sagas.put(saga.id(), saga);
    }

    @Override
    public void update(OwnerOnboarding saga) {
        sagas.put(saga.id(), saga);
    }

    @Override
    public List<OwnerOnboarding> findRunningSince(Instant updatedBefore) {
        return sagas.values().stream()
                .filter(s -> s.status() == SagaStatus.RUNNING && s.updatedAt().isBefore(updatedBefore)).toList();
    }
}
