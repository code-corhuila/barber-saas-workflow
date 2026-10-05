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

| Operation | Contract |
|---|---|
| `POST /api/v1/sagas/owner-onboarding` | No token (a sign-up); `Idempotency-Key` required; 201 with the saga in its final status (`COMPLETED`, `COMPENSATED`, `FAILED`), or 200 on a retry; 400 field by field |
| `GET /api/v1/sagas/{id}` | `SUPER_ADMIN`, or the owner of the barbershop the saga created; anything else 404 |
| `GET /health` | liveness, no token |

### How to start it

As part of the platform: `./scripts/up.sh dev` in `barber-saas-infra`, which migrates the schema
with `workflow-db-migrate` and starts the `workflow` service. Alone, without a database (in-memory
store) and with the participants wherever they run: see `.env.example`, then

```bash
mvn -B -DskipTests package
java -jar workflow-app/target/workflow-app-0.1.0.jar
```

### Where the data is

Schema `workflow` of the shared PostgreSQL instance, table `saga`, as `workflow_app`
(`DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`). Its changelog is `db/`, applied by
`workflow-db-migrate` with the tables `databasechangelog_workflow` (ADR-009, Annex J J.6). The
owner's password is never stored.

### How it is tested

`mvn -B verify` (no Docker needed): the saga with fake participants, the participants against a
local HTTP server, and the whole HTTP contract with both participants faked over real HTTP.
`JdbcSagaStoreTest` also runs against a real schema when `TEST_DATABASE_URL`,
`TEST_DATABASE_USER` and `TEST_DATABASE_PASSWORD` are set. `db-ci.yml` rebuilds the schema.

### What is missing

The gateway route for `/api/v1/sagas` and the include in `barber-saas-infra`; the sign-up screen in
`barber-saas-identity-auth-app`; the business process view of the saga in `16-bpmn`.
