package co.edu.corhuila.barbersaas.workflow.adapter.in.http;

import co.edu.corhuila.barbersaas.workflow.adapter.in.http.ApiError.FieldError;
import co.edu.corhuila.barbersaas.workflow.adapter.in.http.ApiError.ValidationException;
import co.edu.corhuila.barbersaas.workflow.application.port.in.OwnerOnboardingUseCases;
import co.edu.corhuila.barbersaas.workflow.application.port.in.OwnerOnboardingUseCases.Barbershop;
import co.edu.corhuila.barbersaas.workflow.application.port.in.OwnerOnboardingUseCases.Owner;
import co.edu.corhuila.barbersaas.workflow.application.port.in.OwnerOnboardingUseCases.Reader;
import co.edu.corhuila.barbersaas.workflow.application.port.in.OwnerOnboardingUseCases.Started;
import co.edu.corhuila.barbersaas.workflow.domain.OwnerOnboarding;
import co.edu.corhuila.barbersaas.workflow.domain.OwnerOnboardingStep;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP to use case for workflow-service.yaml. It validates everything the participants would
 * refuse, so that the only refusal left in the saga is an e-mail already registered.
 */
@RestController
@RequestMapping("/api/v1/sagas")
public class SagaController {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    public record OwnerRequest(String fullName, String email, String password, String phone) { }

    public record BarbershopRequest(String name, String city, String address, String phone, Double latitude,
                                    Double longitude) { }

    public record OwnerOnboardingRequest(OwnerRequest owner, BarbershopRequest barbershop) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SagaResponse(UUID id, String type, String status, List<String> completedSteps, String failedStep,
                               String failureReason, UUID barbershopId, UUID userId) {
        static SagaResponse of(OwnerOnboarding s) {
            return new SagaResponse(s.id(), "owner-onboarding", s.status().name(),
                    s.completedSteps().stream().map(OwnerOnboardingStep::wireName).toList(),
                    s.failedStep() == null ? null : s.failedStep().wireName(),
                    s.failureReason() == null ? null : s.failureReason().name(), s.barbershopId(), s.userId());
        }
    }

    private final OwnerOnboardingUseCases onboarding;

    public SagaController(OwnerOnboardingUseCases onboarding) {
        this.onboarding = onboarding;
    }

    /** No token: it is a sign-up (DEC-WF-01). Every run answers 201 with its outcome (DEC-WF-02). */
    @PostMapping("/owner-onboarding")
    public ResponseEntity<SagaResponse> start(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) OwnerOnboardingRequest body) {
        List<FieldError> errors = new ArrayList<>();
        if (idempotencyKey == null || idempotencyKey.length() < 8 || idempotencyKey.length() > 128) {
            errors.add(new FieldError("Idempotency-Key", "required, between 8 and 128 characters"));
        }
        if (body == null || body.owner() == null || body.barbershop() == null) {
            errors.add(new FieldError(body == null || body.owner() == null ? "owner" : "barbershop", "required"));
            throw new ValidationException("the request is not valid", errors);
        }
        validateOwner(errors, body.owner());
        validateBarbershop(errors, body.barbershop());
        if (!errors.isEmpty()) {
            throw new ValidationException("the request is not valid", errors);
        }
        OwnerRequest o = body.owner();
        BarbershopRequest b = body.barbershop();
        Started started = onboarding.start(new Owner(o.fullName().strip(), o.email().strip(), o.password(), o.phone()),
                new Barbershop(b.name().strip(), b.city().strip(), b.address(), b.phone(), b.latitude(), b.longitude()),
                idempotencyKey);
        if (!started.created()) {
            return ResponseEntity.ok(SagaResponse.of(started.saga()));
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .location(URI.create("/api/v1/sagas/" + started.saga().id()))
                .body(SagaResponse.of(started.saga()));
    }

    /** SUPER_ADMIN reads any saga; an owner only the one that created their barbershop; else 404. */
    @GetMapping("/{id}")
    public SagaResponse get(HttpServletRequest request, @PathVariable UUID id) {
        Caller caller = AuthFilter.caller(request);
        Reader reader = new Reader(caller.hasRole("SUPER_ADMIN"),
                caller.hasRole("ADMIN_BARBERSHOP") ? caller.barbershopId() : null);
        return onboarding.find(id, reader).map(SagaResponse::of).orElseThrow(ApiError.NotFoundException::new);
    }

    private static void validateOwner(List<FieldError> errors, OwnerRequest o) {
        text(errors, "owner.fullName", o.fullName(), 120);
        text(errors, "owner.email", o.email(), 150);
        if (o.email() != null && !EMAIL.matcher(o.email().strip()).matches()) {
            errors.add(new FieldError("owner.email", "not a valid e-mail"));
        }
        String p = o.password();
        // The same policy identity-auth applies, so the owner step never fails on it.
        if (p == null || p.length() < 8 || p.length() > 100 || p.chars().noneMatch(Character::isUpperCase)
                || p.chars().noneMatch(Character::isDigit)) {
            errors.add(new FieldError("owner.password", "8 to 100 characters, one uppercase letter and one digit"));
        }
        maxLength(errors, "owner.phone", o.phone(), 20);
    }

    private static void validateBarbershop(List<FieldError> errors, BarbershopRequest b) {
        text(errors, "barbershop.name", b.name(), 120);
        text(errors, "barbershop.city", b.city(), 80);
        maxLength(errors, "barbershop.address", b.address(), 255);
        maxLength(errors, "barbershop.phone", b.phone(), 20);
        if (b.latitude() != null && Math.abs(b.latitude()) > 90) {
            errors.add(new FieldError("barbershop.latitude", "between -90 and 90"));
        }
        if (b.longitude() != null && Math.abs(b.longitude()) > 180) {
            errors.add(new FieldError("barbershop.longitude", "between -180 and 180"));
        }
    }

    private static void text(List<FieldError> errors, String field, String value, int max) {
        if (value == null || value.isBlank()) {
            errors.add(new FieldError(field, "required"));
        } else {
            maxLength(errors, field, value.strip(), max);
        }
    }

    private static void maxLength(List<FieldError> errors, String field, String value, int max) {
        if (value != null && value.length() > max) {
            errors.add(new FieldError(field, "at most " + max + " characters"));
        }
    }
}
