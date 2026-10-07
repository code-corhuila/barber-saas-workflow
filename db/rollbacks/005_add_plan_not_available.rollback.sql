ALTER TABLE workflow.saga
    DROP CONSTRAINT chk_saga_reason,
    ADD CONSTRAINT chk_saga_reason CHECK (failure_reason IN
        ('EMAIL_ALREADY_REGISTERED', 'STEP_UNAVAILABLE', 'INTERRUPTED'));
