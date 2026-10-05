package co.edu.corhuila.barbersaas.workflow.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

/** workflow-service.yaml end to end, with barbershop-api and identity-auth-api faked over real HTTP. */
@SpringBootTest
@AutoConfigureMockMvc
class OwnerOnboardingHttpTest {

    private static final List<String> CALLS = new CopyOnWriteArrayList<>();
    private static volatile int ownerStatus = 201;
    private static final HttpServer PARTICIPANTS = participants();

    @Autowired
    private MockMvc http;

    @Autowired
    private ObjectMapper json;

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) {
        TestKeys.register(registry);
        String url = "http://127.0.0.1:" + PARTICIPANTS.getAddress().getPort();
        registry.add("BARBERSHOP_API_URL", () -> url);
        registry.add("IDENTITY_AUTH_API_URL", () -> url);
        registry.add("PARTICIPANT_BACKOFF_MS", () -> "0");
    }

    @AfterAll
    static void stop() {
        PARTICIPANTS.stop(0);
    }

    @BeforeEach
    void reset() {
        CALLS.clear();
        ownerStatus = 201;
    }

    @Test
    void an_owner_signs_up_without_a_token_and_the_saga_completes() throws Exception {
        JsonNode saga = body(http.perform(start("onboard-0001", "andres@example.com", "SecurePass123"))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.type").value("owner-onboarding"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.completedSteps[0]").value("create-barbershop"))
                .andExpect(jsonPath("$.completedSteps[1]").value("create-owner"))
                .andExpect(jsonPath("$.failedStep").doesNotExist())
                .andReturn().getResponse().getContentAsString());

        String id = saga.get("id").asText();
        assertEquals(List.of("POST /internal/v1/barbershops " + id + ":create-barbershop Bearer workflow-service-token",
                "POST /internal/v1/owners " + id + ":create-owner Bearer workflow-service-token"), CALLS);
    }

    @Test
    void a_retry_with_the_same_key_returns_the_same_saga_and_calls_nobody() throws Exception {
        String first = http.perform(start("onboard-0002", "maria@example.com", "SecurePass123"))
                .andReturn().getResponse().getContentAsString();
        CALLS.clear();

        http.perform(start("onboard-0002", "maria@example.com", "SecurePass123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(body(first).get("id").asText()));
        assertTrue(CALLS.isEmpty());
    }

    @Test
    void an_email_already_registered_removes_the_barbershop() throws Exception {
        ownerStatus = 422;

        http.perform(start("onboard-0003", "taken@example.com", "SecurePass123"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPENSATED"))
                .andExpect(jsonPath("$.failedStep").value("create-owner"))
                .andExpect(jsonPath("$.failureReason").value("EMAIL_ALREADY_REGISTERED"))
                .andExpect(jsonPath("$.userId").doesNotExist());
        assertTrue(CALLS.get(2).startsWith("DELETE /internal/v1/barbershops/"));
    }

    @Test
    void a_weak_password_is_refused_before_any_step_runs() throws Exception {
        http.perform(start("onboard-0004", "weak@example.com", "password"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0].field").value("owner.password"));
        assertTrue(CALLS.isEmpty());
    }

    @Test
    void the_key_is_required() throws Exception {
        http.perform(post("/api/v1/sagas/owner-onboarding").contentType(MediaType.APPLICATION_JSON)
                        .content(request("nokey@example.com", "SecurePass123")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("Idempotency-Key"));
    }

    @Test
    void only_the_owner_of_the_new_barbershop_or_a_super_admin_reads_the_saga() throws Exception {
        JsonNode saga = body(http.perform(start("onboard-0005", "reader@example.com", "SecurePass123"))
                .andReturn().getResponse().getContentAsString());
        String path = "/api/v1/sagas/" + saga.get("id").asText();
        UUID barbershop = UUID.fromString(saga.get("barbershopId").asText());

        http.perform(get(path)).andExpect(status().isUnauthorized());
        http.perform(get(path).header("Authorization", bearer("ADMIN_BARBERSHOP", barbershop)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
        http.perform(get(path).header("Authorization", bearer("ADMIN_BARBERSHOP", UUID.randomUUID())))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("NOT_FOUND"));
        http.perform(get(path).header("Authorization", bearer("CLIENT", null))).andExpect(status().isNotFound());
        http.perform(get(path).header("Authorization", bearer("SUPER_ADMIN", null))).andExpect(status().isOk());
        http.perform(get("/api/v1/sagas/not-a-uuid").header("Authorization", bearer("SUPER_ADMIN", null)))
                .andExpect(status().isNotFound());
    }

    private RequestBuilder start(String key, String email, String password) {
        return post("/api/v1/sagas/owner-onboarding").header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(request(email, password));
    }

    private static String request(String email, String password) {
        return """
                {"owner":{"fullName":"Andres Rojas","email":"%s","password":"%s"},
                 "barbershop":{"name":"El Clasico","city":"Neiva","address":"Calle 10 # 5-20"}}
                """.formatted(email, password);
    }

    private static String bearer(String role, UUID barbershopId) throws Exception {
        return "Bearer " + TestKeys.token(UUID.randomUUID().toString(), role, barbershopId);
    }

    private JsonNode body(String content) throws Exception {
        return json.readTree(content);
    }

    /** barbershop-api and identity-auth-api as they answer the saga (DEC-SHOP-05, DEC-AUTH-04). */
    private static HttpServer participants() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/internal/v1/", exchange -> {
                String method = exchange.getRequestMethod();
                String path = exchange.getRequestURI().getPath();
                String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
                CALLS.add(method + " " + path + (key == null ? "" : " " + key + " "
                        + exchange.getRequestHeaders().getFirst("Authorization")));
                int status = method.equals("DELETE") ? 204 : path.endsWith("/owners") ? ownerStatus : 201;
                byte[] body = (status == 422 ? "{\"error\":\"BUSINESS_RULE_VIOLATION\"}"
                        : "{\"id\":\"" + UUID.randomUUID() + "\"}").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, status == 204 ? -1 : body.length);
                if (status != 204) {
                    exchange.getResponseBody().write(body);
                }
                exchange.close();
            });
            server.start();
            return server;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
