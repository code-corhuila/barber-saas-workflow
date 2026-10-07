package co.edu.corhuila.barbersaas.workflow.adapter.out.http;

import co.edu.corhuila.barbersaas.workflow.application.port.in.OwnerOnboardingUseCases.Barbershop;
import co.edu.corhuila.barbersaas.workflow.application.port.in.OwnerOnboardingUseCases.Owner;
import co.edu.corhuila.barbersaas.workflow.application.port.out.Participants.BarbershopParticipant;
import co.edu.corhuila.barbersaas.workflow.application.port.out.Participants.OwnerParticipant;
import co.edu.corhuila.barbersaas.workflow.application.port.out.Participants.PlanParticipant;
import co.edu.corhuila.barbersaas.workflow.application.port.out.Participants.StepRejected;
import co.edu.corhuila.barbersaas.workflow.application.port.out.Participants.StepUnavailable;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The internal operations the saga calls (07-api/authentication.md, internal operations):
 * barbershop-api DEC-SHOP-05, platform-admin-api DEC-PLAT-04 and identity-auth-api DEC-AUTH-04. Nothing here decides the saga;
 * it only turns HTTP answers into a result, StepRejected or StepUnavailable.
 */
public class HttpParticipants implements BarbershopParticipant, PlanParticipant, OwnerParticipant {

    private final ParticipantClient client;
    private final ObjectMapper json;
    private final String barbershopUrl;
    private final String platformAdminUrl;
    private final String identityUrl;

    public HttpParticipants(ParticipantClient client, ObjectMapper json, String barbershopUrl, String platformAdminUrl,
                            String identityUrl) {
        this.client = client;
        this.json = json;
        this.barbershopUrl = stripSlash(barbershopUrl);
        this.platformAdminUrl = stripSlash(platformAdminUrl);
        this.identityUrl = stripSlash(identityUrl);
    }

    @Override
    public UUID createBarbershop(String stepKey, Barbershop barbershop) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", barbershop.name());
        body.put("city", barbershop.city());
        putIfPresent(body, "address", barbershop.address());
        putIfPresent(body, "phone", barbershop.phone());
        putIfPresent(body, "latitude", barbershop.latitude());
        putIfPresent(body, "longitude", barbershop.longitude());
        return createdId(post(barbershopUrl + "/internal/v1/barbershops", stepKey, body), "barbershop-api");
    }

    @Override
    public void deleteBarbershop(UUID barbershopId) {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                URI.create(barbershopUrl + "/internal/v1/barbershops/" + barbershopId)).DELETE(), "barbershop-api");
        // 204 also when it was already gone (DEC-SHOP-05); 404 counts as removed too.
        if (response.statusCode() != 204 && response.statusCode() != 404) {
            throw new StepRejected("barbershop-api refused the removal with " + response.statusCode());
        }
    }

    /** 204; a 4xx is the refusal of an unknown or inactive plan (DEC-PLAT-04). */
    @Override
    public void assignPlan(String stepKey, UUID barbershopId, UUID planId) {
        String body;
        try {
            body = json.writeValueAsString(Map.of("planId", planId.toString()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                        URI.create(platformAdminUrl + "/internal/v1/barbershops/" + barbershopId + "/plan"))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", stepKey)
                .PUT(HttpRequest.BodyPublishers.ofString(body)), "platform-admin-api");
        if (response.statusCode() != 204 && response.statusCode() != 200) {
            throw new StepRejected("platform-admin-api answered " + response.statusCode() + " " + errorCode(response));
        }
    }

    @Override
    public UUID createOwner(String stepKey, Owner owner, UUID barbershopId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fullName", owner.fullName());
        body.put("email", owner.email());
        body.put("password", owner.password());
        putIfPresent(body, "phone", owner.phone());
        body.put("barbershopId", barbershopId.toString());
        return createdId(post(identityUrl + "/internal/v1/owners", stepKey, body), "identity-auth-api");
    }

    private HttpResponse<String> post(String url, String stepKey, Map<String, Object> body) {
        try {
            return client.send(HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Idempotency-Key", stepKey)
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))), url);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 201, or 200 when the step was retried with the same key: both carry the resource and its id. */
    private UUID createdId(HttpResponse<String> response, String participant) {
        int status = response.statusCode();
        if (status != 200 && status != 201) {
            // Never the participant's body: it may echo what was sent (annex E, failure detail).
            throw new StepRejected(participant + " answered " + status + " " + errorCode(response));
        }
        try {
            return UUID.fromString(json.readTree(response.body()).path("id").asText());
        } catch (Exception e) {
            throw new StepUnavailable(participant + " answered " + status + " without a readable id");
        }
    }

    private String errorCode(HttpResponse<String> response) {
        try {
            JsonNode error = json.readTree(response.body()).path("error");
            return error.isTextual() ? error.asText() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static void putIfPresent(Map<String, Object> body, String field, Object value) {
        if (value != null) {
            body.put(field, value);
        }
    }

    private static String stripSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
