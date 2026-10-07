package co.edu.corhuila.barbersaas.workflow.domain;

/** The closed list a client can act on (workflow-service.yaml, failureReason). */
public enum FailureReason {
    /** The owner's e-mail already has an account: log in or use another e-mail. */
    EMAIL_ALREADY_REGISTERED,
    /** A participant did not answer after the bounded retries: sign up again. */
    STEP_UNAVAILABLE,
    /** The workflow restarted in the middle of the saga (DEC-WF-03): sign up again. */
    INTERRUPTED,
    /** The plan the owner picked is unknown or no longer active (DEC-WF-05): pick another plan. */
    PLAN_NOT_AVAILABLE
}
