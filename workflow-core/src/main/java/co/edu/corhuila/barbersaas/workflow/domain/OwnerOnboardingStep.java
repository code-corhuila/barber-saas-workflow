package co.edu.corhuila.barbersaas.workflow.domain;

/** The steps of owner-onboarding, in order. Only create-barbershop has a compensation (annex E, rule 1). */
public enum OwnerOnboardingStep {
    CREATE_BARBERSHOP("create-barbershop"),
    CREATE_OWNER("create-owner");

    private final String wireName;

    OwnerOnboardingStep(String wireName) {
        this.wireName = wireName;
    }

    /** The name in the contract, the saga table and the Idempotency-Key of the step. */
    public String wireName() {
        return wireName;
    }

    public static OwnerOnboardingStep fromWireName(String name) {
        for (OwnerOnboardingStep step : values()) {
            if (step.wireName.equals(name)) {
                return step;
            }
        }
        throw new IllegalArgumentException("unknown step " + name);
    }
}
