-- DEC-WF-05: a saga compensated because the chosen plan is unknown or inactive ends with
-- PLAN_NOT_AVAILABLE. New changeset: the applied ones are never edited (Liquibase checksums).
ALTER TABLE workflow.saga
    DROP CONSTRAINT chk_saga_reason,
    ADD CONSTRAINT chk_saga_reason CHECK (failure_reason IN
        ('EMAIL_ALREADY_REGISTERED', 'STEP_UNAVAILABLE', 'INTERRUPTED', 'PLAN_NOT_AVAILABLE'));
