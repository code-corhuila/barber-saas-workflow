package co.edu.corhuila.barbersaas.workflow.adapter.out.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.corhuila.barbersaas.workflow.application.port.in.OwnerOnboardingUseCases.Barbershop;
import co.edu.corhuila.barbersaas.workflow.application.port.in.OwnerOnboardingUseCases.Owner;
import co.edu.corhuila.barbersaas.workflow.application.port.out.Participants.StepRejected;
import co.edu.corhuila.barbersaas.workflow.application.port.out.Participants.StepUnavailable;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/** The participant adapters against a real local HTTP server. */
class HttpParticipantsTest {

    private static final Barbershop SHOP = new Barbershop("El Clasico", "Neiva", null, null, 2.93, -75.28);
    private static final Owner OWNER = new Owner("Andres Rojas", "andres@example.com", "SecurePass123", null);

    private HttpServer server;
    private final ConcurrentLinkedQueue<Integer> statuses = new ConcurrentLinkedQueue<>();
    private final List<HttpExchange> received = new ArrayList<>();
    private final List<String> bodies = new ArrayList<>();
    private String answerBody = "";
    private HttpParticipants participants;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            synchronized (received) {
                received.add(exchange);
                bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            }
            Integer status = statuses.isEmpty() ? 201 : statuses.poll();
            byte[] body = answerBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, status == 204 ? -1 : body.length);
            if (status != 204) {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        ParticipantClient client = new ParticipantClient(HttpClient.newHttpClient(), "service-token",
                Duration.ofSeconds(2), 3, Duration.ZERO);
        participants = new HttpParticipants(client, new ObjectMapper(), base + "/", base);
    }

    @AfterEach
    void stop() {
        server.stop(0);
        MDC.clear();
    }

    @Test
    void create_barbershop_sends_the_step_key_the_service_token_and_the_correlation_id() {
        UUID id = UUID.randomUUID();
        answerBody = "{\"id\":\"" + id + "\",\"status\":\"TRIAL\"}";
        MDC.put("correlationId", "corr-1");

        assertEquals(id, participants.createBarbershop("saga-1:create-barbershop", SHOP));

        HttpExchange call = received.get(0);
        assertEquals("POST", call.getRequestMethod());
        assertEquals("/internal/v1/barbershops", call.getRequestURI().getPath());
        assertEquals("saga-1:create-barbershop", call.getRequestHeaders().getFirst("Idempotency-Key"));
        assertEquals("Bearer service-token", call.getRequestHeaders().getFirst("Authorization"));
        assertEquals("corr-1", call.getRequestHeaders().getFirst("X-Correlation-Id"));
        assertTrue(bodies.get(0).contains("\"city\":\"Neiva\""));
        assertFalse(bodies.get(0).contains("address"));
    }

    @Test
    void a_retried_step_answered_200_returns_the_same_id() {
        UUID id = UUID.randomUUID();
        answerBody = "{\"id\":\"" + id + "\"}";
        statuses.add(200);

        assertEquals(id, participants.createOwner("saga-1:create-owner", OWNER, UUID.randomUUID()));
    }

    @Test
    void a_5xx_is_retried_and_then_succeeds() {
        UUID id = UUID.randomUUID();
        answerBody = "{\"id\":\"" + id + "\"}";
        statuses.add(503);
        statuses.add(502);

        assertEquals(id, participants.createBarbershop("saga-1:create-barbershop", SHOP));
        assertEquals(3, received.size());
    }

    @Test
    void a_participant_that_keeps_failing_is_unavailable_after_the_bounded_retries() {
        statuses.add(503);
        statuses.add(503);
        statuses.add(429);

        assertThrows(StepUnavailable.class, () -> participants.createBarbershop("saga-1:create-barbershop", SHOP));
        assertEquals(3, received.size());
    }

    @Test
    void a_4xx_is_a_refusal_and_is_not_retried() {
        answerBody = "{\"error\":\"BUSINESS_RULE_VIOLATION\",\"message\":\"The email is already registered\"}";
        statuses.add(422);

        StepRejected e = assertThrows(StepRejected.class,
                () -> participants.createOwner("saga-1:create-owner", OWNER, UUID.randomUUID()));
        assertTrue(e.getMessage().contains("422 BUSINESS_RULE_VIOLATION"));
        assertEquals(1, received.size());
    }

    @Test
    void create_owner_sends_the_barbershop_of_the_first_step() {
        UUID barbershop = UUID.randomUUID();
        answerBody = "{\"id\":\"" + UUID.randomUUID() + "\"}";

        participants.createOwner("saga-1:create-owner", OWNER, barbershop);

        assertEquals("/internal/v1/owners", received.get(0).getRequestURI().getPath());
        assertTrue(bodies.get(0).contains("\"barbershopId\":\"" + barbershop + "\""));
    }

    @Test
    void removing_a_barbershop_that_is_already_gone_counts_as_removed() {
        statuses.add(204);
        statuses.add(404);

        participants.deleteBarbershop(UUID.randomUUID());
        participants.deleteBarbershop(UUID.randomUUID());

        assertEquals("DELETE", received.get(0).getRequestMethod());
    }

    @Test
    void a_removal_the_participant_refuses_is_a_refusal() {
        statuses.add(422);

        assertThrows(StepRejected.class, () -> participants.deleteBarbershop(UUID.randomUUID()));
    }
}
