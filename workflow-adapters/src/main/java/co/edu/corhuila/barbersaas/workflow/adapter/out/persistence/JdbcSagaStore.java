package co.edu.corhuila.barbersaas.workflow.adapter.out.persistence;

import co.edu.corhuila.barbersaas.workflow.application.port.out.SagaStore;
import co.edu.corhuila.barbersaas.workflow.domain.FailureReason;
import co.edu.corhuila.barbersaas.workflow.domain.OwnerOnboarding;
import co.edu.corhuila.barbersaas.workflow.domain.OwnerOnboardingStep;
import co.edu.corhuila.barbersaas.workflow.domain.SagaStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Reads and writes workflow.saga (ADR-009) as workflow_app. Each call is its own transaction. */
public class JdbcSagaStore implements SagaStore {

    private static final String TYPE = "owner-onboarding";
    private static final String COLUMNS = "id, status, completed_steps, failed_step, failure_reason, failure_detail, "
            + "barbershop_id, user_id, idempotency_key, request_hash, created_at, updated_at";

    private final JdbcTemplate jdbc;

    public JdbcSagaStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<OwnerOnboarding> findById(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM workflow.saga WHERE id = ? AND type = ?",
                (rs, n) -> map(rs), id, TYPE).stream().findFirst();
    }

    @Override
    public Optional<OwnerOnboarding> findByKey(String idempotencyKey) {
        return jdbc.query("SELECT " + COLUMNS + " FROM workflow.saga WHERE idempotency_key = ? AND type = ?",
                (rs, n) -> map(rs), idempotencyKey, TYPE).stream().findFirst();
    }

    @Override
    public void insert(OwnerOnboarding s) {
        try {
            jdbc.update("INSERT INTO workflow.saga (id, type, status, completed_steps, idempotency_key, request_hash, "
                            + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    s.id(), TYPE, s.status().name(), steps(s), s.idempotencyKey(), s.requestHash(),
                    Timestamp.from(s.createdAt()), Timestamp.from(s.updatedAt()));
        } catch (DuplicateKeyException e) {
            throw new KeyTaken();
        }
    }

    @Override
    public void update(OwnerOnboarding s) {
        jdbc.update("UPDATE workflow.saga SET status = ?, completed_steps = ?, failed_step = ?, failure_reason = ?, "
                        + "failure_detail = ?, barbershop_id = ?, user_id = ?, updated_at = ? WHERE id = ?",
                s.status().name(), steps(s), s.failedStep() == null ? null : s.failedStep().wireName(),
                s.failureReason() == null ? null : s.failureReason().name(), s.failureDetail(), s.barbershopId(),
                s.userId(), Timestamp.from(s.updatedAt()), s.id());
    }

    @Override
    public List<OwnerOnboarding> findRunningSince(Instant updatedBefore) {
        return jdbc.query("SELECT " + COLUMNS + " FROM workflow.saga WHERE status = 'RUNNING' AND updated_at < ? "
                + "AND type = ? ORDER BY updated_at", (rs, n) -> map(rs), Timestamp.from(updatedBefore), TYPE);
    }

    private static String[] steps(OwnerOnboarding s) {
        return s.completedSteps().stream().map(OwnerOnboardingStep::wireName).toArray(String[]::new);
    }

    private static OwnerOnboarding map(ResultSet rs) throws SQLException {
        String[] steps = (String[]) rs.getArray("completed_steps").getArray();
        String failedStep = rs.getString("failed_step");
        String reason = rs.getString("failure_reason");
        return new OwnerOnboarding(rs.getObject("id", UUID.class), rs.getString("idempotency_key"),
                rs.getString("request_hash"), SagaStatus.valueOf(rs.getString("status")),
                Arrays.stream(steps).map(OwnerOnboardingStep::fromWireName).toList(),
                failedStep == null ? null : OwnerOnboardingStep.fromWireName(failedStep),
                reason == null ? null : FailureReason.valueOf(reason), rs.getString("failure_detail"),
                rs.getObject("barbershop_id", UUID.class), rs.getObject("user_id", UUID.class),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }
}
