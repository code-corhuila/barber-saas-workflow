-- One row per saga run, written after every step and every compensation (annex E, ADR-009).
-- The owner's password is never stored here (DEC-WF-03); failure_detail is for people reading the
-- table and the log, never for the HTTP response.
CREATE TABLE workflow.saga (
    id               uuid        NOT NULL,
    type             text        NOT NULL,
    status           text        NOT NULL,
    completed_steps  text[]      NOT NULL DEFAULT '{}',
    failed_step      text        NULL,
    failure_reason   text        NULL,
    failure_detail   text        NULL,
    barbershop_id    uuid        NULL,      -- no FK: barbershop domain
    user_id          uuid        NULL,      -- no FK: identity-auth domain
    idempotency_key  text        NOT NULL,
    request_hash     text        NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_saga PRIMARY KEY (id),
    CONSTRAINT uq_saga_idempotency_key UNIQUE (type, idempotency_key),
    CONSTRAINT chk_saga_type   CHECK (type IN ('owner-onboarding')),
    CONSTRAINT chk_saga_status CHECK (status IN ('RUNNING', 'COMPLETED', 'COMPENSATED', 'FAILED')),
    CONSTRAINT chk_saga_key    CHECK (char_length(idempotency_key) BETWEEN 8 AND 128),
    CONSTRAINT chk_saga_failed_step CHECK ((status IN ('COMPENSATED', 'FAILED')) = (failed_step IS NOT NULL)),
    CONSTRAINT chk_saga_reason CHECK (failure_reason IN ('EMAIL_ALREADY_REGISTERED', 'STEP_UNAVAILABLE', 'INTERRUPTED'))
);
-- Sagas left RUNNING by a restart are found by age (DEC-WF-03).
CREATE INDEX idx_saga_running ON workflow.saga (updated_at) WHERE status = 'RUNNING';
-- An owner reads the saga that created their barbershop.
CREATE INDEX idx_saga_barbershop_id ON workflow.saga (barbershop_id);
