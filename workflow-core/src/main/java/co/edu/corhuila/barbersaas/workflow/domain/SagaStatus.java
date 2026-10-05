package co.edu.corhuila.barbersaas.workflow.domain;

/** Annex E. RUNNING is seen only while a saga is in progress, or when a restart interrupted it. */
public enum SagaStatus {
    RUNNING, COMPLETED, COMPENSATED, FAILED
}
