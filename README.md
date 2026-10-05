# barber-saas-workflow

> Business process orchestration (saga)

Part of the **LMS Library** distributed system — team `lms-library`, Grupo 2.
Governance and documentation live in [`library-docs`](https://github.com/code-corhuila/library-docs).

## Branching

Three permanent branches. **None of them accepts a direct commit** — you enter through a child
branch and leave through a Pull Request.

```
develop  <--PR--  feat/... fix/... chore/...
qa       <--PR--  qa/...
main     <--PR--  release/...  hotfix/...
```

Promotion happens **by re-application** (`git cherry-pick -x`), never by merging one permanent
branch into another: `merge develop -> qa` and `merge qa -> main` do not exist in this model.

`main` requires **1 approval from `ariel5253`**. On `develop` and `qa` the team sets its own review
rule.

Full policy: `00-governance/branching-policy.md` in `library-docs`.

---

## BarberSaaS — what this repository is

The orchestrator of the processes that cross domains, run as sagas (norm 5.8, annex E). Its
contract is `07-api/contracts/openapi/workflow-service.yaml` in `barber-saas-docs`: the first saga
is `owner-onboarding` (HU-AUTH-003), which creates a barbershop in `TRIAL` and then its owner, and
removes the barbershop if the owner cannot be created. Saga state lives in the `workflow` schema of
the single PostgreSQL instance (ADR-009).

Hexagonal, three Maven modules (ADR-012, annex C): `workflow-core` (sagas and use cases, no
Spring), `workflow-adapters` (HTTP in, HTTP clients to the participants, JDBC) and `workflow-app`
(composition root).

### How it is tested

`mvn -B verify` (no Docker needed).

### What is missing

The `workflow` schema, the `owner-onboarding` saga and its HTTP operations: they arrive in the next
pull requests.
