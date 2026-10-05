package co.edu.corhuila.barbersaas.workflow.application.usecase;

import co.edu.corhuila.barbersaas.workflow.application.port.in.OwnerOnboardingUseCases;
import co.edu.corhuila.barbersaas.workflow.application.port.out.IdGenerator;
import co.edu.corhuila.barbersaas.workflow.application.port.out.Participants.BarbershopParticipant;
import co.edu.corhuila.barbersaas.workflow.application.port.out.Participants.OwnerParticipant;
import co.edu.corhuila.barbersaas.workflow.application.port.out.Participants.StepRejected;
import co.edu.corhuila.barbersaas.workflow.application.port.out.Participants.StepUnavailable;
import co.edu.corhuila.barbersaas.workflow.application.port.out.SagaStore;
import co.edu.corhuila.barbersaas.workflow.domain.FailureReason;
import co.edu.corhuila.barbersaas.workflow.domain.OwnerOnboarding;
import co.edu.corhuila.barbersaas.workflow.domain.OwnerOnboardingStep;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * The orchestrator of owner-onboarding (annex E): runs create-barbershop, then create-owner,
 * writes the saga after every step and, if the owner cannot be created, removes the barbershop.
 * Steps run in this request; the saga answers in its final status (DEC-WF-02).
 */
public class OwnerOnboardingService implements OwnerOnboardingUseCases {

    private static final char SEPARATOR = 0;

    private final SagaStore sagas;
    private final BarbershopParticipant barbershops;
    private final OwnerParticipant owners;
    private final IdGenerator ids;
    private final Clock clock;

    public OwnerOnboardingService(SagaStore sagas, BarbershopParticipant barbershops, OwnerParticipant owners,
                                  IdGenerator ids, Clock clock) {
        this.sagas = sagas;
        this.barbershops = barbershops;
        this.owners = owners;
        this.ids = ids;
        this.clock = clock;
    }

    @Override
    public Started start(Owner owner, Barbershop barbershop, String idempotencyKey) {
        String requestHash = requestHash(owner, barbershop);
        Optional<OwnerOnboarding> previous = sagas.findByKey(idempotencyKey);
        if (previous.isPresent()) {
            return retry(previous.get(), requestHash);
        }
        OwnerOnboarding saga = OwnerOnboarding.start(ids.next(), idempotencyKey, requestHash, clock.instant());
        try {
            sagas.insert(saga);
        } catch (SagaStore.KeyTaken e) {
            return retry(sagas.findByKey(idempotencyKey).orElseThrow(), requestHash);
        }

        UUID barbershopId;
        try {
            barbershopId = barbershops.createBarbershop(saga.stepKey(OwnerOnboardingStep.CREATE_BARBERSHOP), barbershop);
        } catch (StepRejected | StepUnavailable e) {
            // Nothing was done, so nothing is undone.
            saga.compensated(OwnerOnboardingStep.CREATE_BARBERSHOP, FailureReason.STEP_UNAVAILABLE, e.getMessage(),
                    clock.instant());
            sagas.update(saga);
            return new Started(saga, true);
        }
        saga.barbershopCreated(barbershopId, clock.instant());
        sagas.update(saga);

        try {
            UUID userId = owners.createOwner(saga.stepKey(OwnerOnboardingStep.CREATE_OWNER), owner, barbershopId);
            saga.completed(userId, clock.instant());
        } catch (StepRejected e) {
            // The only business refusal left after the workflow's own validation: the e-mail exists.
            compensate(saga, FailureReason.EMAIL_ALREADY_REGISTERED, e.getMessage());
        } catch (StepUnavailable e) {
            compensate(saga, FailureReason.STEP_UNAVAILABLE, e.getMessage());
        }
        sagas.update(saga);
        return new Started(saga, true);
    }

    @Override
    public Optional<OwnerOnboarding> find(UUID id, Reader reader) {
        return sagas.findById(id)
                .filter(saga -> reader.superAdmin()
                        || (reader.barbershopId() != null && reader.barbershopId().equals(saga.barbershopId())));
    }

    @Override
    public int recoverInterrupted(Duration age) {
        int recovered = 0;
        for (OwnerOnboarding saga : sagas.findRunningSince(clock.instant().minus(age))) {
            if (saga.barbershopId() == null) {
                // The barbershop step may have run without being recorded: a person checks it.
                saga.failed(OwnerOnboardingStep.CREATE_BARBERSHOP, FailureReason.INTERRUPTED,
                        "restarted before create-barbershop was recorded; look for Idempotency-Key "
                                + saga.stepKey(OwnerOnboardingStep.CREATE_BARBERSHOP), clock.instant());
            } else {
                // The password is not stored, so create-owner cannot run again (DEC-WF-03).
                compensate(saga, FailureReason.INTERRUPTED, "restarted during create-owner");
            }
            sagas.update(saga);
            recovered++;
        }
        return recovered;
    }

    /** Undoes create-barbershop. If even that fails, the saga ends FAILED for a person to decide. */
    private void compensate(OwnerOnboarding saga, FailureReason reason, String detail) {
        try {
            barbershops.deleteBarbershop(saga.barbershopId());
            saga.compensated(OwnerOnboardingStep.CREATE_OWNER, reason, detail, clock.instant());
        } catch (StepRejected | StepUnavailable e) {
            saga.failed(OwnerOnboardingStep.CREATE_OWNER, reason,
                    detail + "; compensation delete-barbershop failed: " + e.getMessage(), clock.instant());
        }
    }

    private static Started retry(OwnerOnboarding previous, String requestHash) {
        if (!previous.requestHash().equals(requestHash)) {
            throw new IdempotencyKeyReused();
        }
        return new Started(previous, false);
    }

    /** Every field but the password, which is never stored, not even hashed (DEC-WF-03). */
    static String requestHash(Owner owner, Barbershop barbershop) {
        String joined = String.join(String.valueOf(SEPARATOR), String.valueOf(owner.fullName()),
                String.valueOf(owner.email()), String.valueOf(owner.phone()), String.valueOf(barbershop.name()),
                String.valueOf(barbershop.city()), String.valueOf(barbershop.address()),
                String.valueOf(barbershop.phone()), String.valueOf(barbershop.latitude()),
                String.valueOf(barbershop.longitude()));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(joined.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
