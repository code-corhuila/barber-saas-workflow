package co.edu.corhuila.barbersaas.workflow.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One run of the owner-onboarding saga (workflow-service.yaml): its state after every step and
 * every compensation. It never holds the owner's password (DEC-WF-03). A finished saga does not
 * change again.
 */
public final class OwnerOnboarding {

    private final UUID id;
    private final String idempotencyKey;
    private final String requestHash;
    private final Instant createdAt;
    private final List<OwnerOnboardingStep> completedSteps;
    private SagaStatus status;
    private OwnerOnboardingStep failedStep;
    private FailureReason failureReason;
    private String failureDetail;
    private UUID barbershopId;
    private UUID userId;
    private Instant updatedAt;

    @SuppressWarnings("java:S107") // the rebuild from storage needs every column
    public OwnerOnboarding(UUID id, String idempotencyKey, String requestHash, SagaStatus status,
                           List<OwnerOnboardingStep> completedSteps, OwnerOnboardingStep failedStep,
                           FailureReason failureReason, String failureDetail, UUID barbershopId, UUID userId,
                           Instant createdAt, Instant updatedAt) {
        this.id = Objects.requireNonNull(id);
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey);
        this.requestHash = Objects.requireNonNull(requestHash);
        this.status = Objects.requireNonNull(status);
        this.completedSteps = new ArrayList<>(completedSteps);
        this.failedStep = failedStep;
        this.failureReason = failureReason;
        this.failureDetail = failureDetail;
        this.barbershopId = barbershopId;
        this.userId = userId;
        this.createdAt = Objects.requireNonNull(createdAt);
        this.updatedAt = Objects.requireNonNull(updatedAt);
    }

    public static OwnerOnboarding start(UUID id, String idempotencyKey, String requestHash, Instant now) {
        return new OwnerOnboarding(id, idempotencyKey, requestHash, SagaStatus.RUNNING, List.of(), null, null, null,
                null, null, now, now);
    }

    /** The Idempotency-Key a step sends to its participant (annex E): a retried step never runs twice. */
    public String stepKey(OwnerOnboardingStep step) {
        return id + ":" + step.wireName();
    }

    public void barbershopCreated(UUID barbershopId, Instant now) {
        requireRunning();
        this.barbershopId = Objects.requireNonNull(barbershopId);
        completedSteps.add(OwnerOnboardingStep.CREATE_BARBERSHOP);
        updatedAt = now;
    }

    /** The plan the owner picked is assigned to the barbershop just created (DEC-WF-05). */
    public void planAssigned(Instant now) {
        requireRunning();
        if (barbershopId == null) {
            throw new IllegalStateException("the plan step runs after the barbershop step");
        }
        completedSteps.add(OwnerOnboardingStep.ASSIGN_PLAN);
        updatedAt = now;
    }

    public void completed(UUID userId, Instant now) {
        requireRunning();
        if (barbershopId == null) {
            throw new IllegalStateException("the owner step runs after the barbershop step");
        }
        this.userId = Objects.requireNonNull(userId);
        completedSteps.add(OwnerOnboardingStep.CREATE_OWNER);
        status = SagaStatus.COMPLETED;
        updatedAt = now;
    }

    /** Every completed step was undone. The barbershop no longer exists, but its id stays for the record. */
    public void compensated(OwnerOnboardingStep failed, FailureReason reason, String detail, Instant now) {
        end(SagaStatus.COMPENSATED, failed, reason, detail, now);
    }

    /** A compensation failed too: automatic recovery is no longer safe and a person decides (annex E). */
    public void failed(OwnerOnboardingStep failed, FailureReason reason, String detail, Instant now) {
        end(SagaStatus.FAILED, failed, reason, detail, now);
    }

    private void end(SagaStatus end, OwnerOnboardingStep failed, FailureReason reason, String detail, Instant now) {
        requireRunning();
        status = end;
        failedStep = Objects.requireNonNull(failed);
        failureReason = Objects.requireNonNull(reason);
        failureDetail = detail;
        updatedAt = now;
    }

    private void requireRunning() {
        if (status != SagaStatus.RUNNING) {
            throw new IllegalStateException("saga " + id + " already ended " + status);
        }
    }

    public UUID id() { return id; }
    public String idempotencyKey() { return idempotencyKey; }
    public String requestHash() { return requestHash; }
    public SagaStatus status() { return status; }
    public List<OwnerOnboardingStep> completedSteps() { return List.copyOf(completedSteps); }
    public OwnerOnboardingStep failedStep() { return failedStep; }
    public FailureReason failureReason() { return failureReason; }
    public String failureDetail() { return failureDetail; }
    public UUID barbershopId() { return barbershopId; }
    public UUID userId() { return userId; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
}
