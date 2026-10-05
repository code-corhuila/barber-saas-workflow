package co.edu.corhuila.barbersaas.workflow.app;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class HealthHttpTest {

    @Autowired
    private MockMvc http;

    @Test
    void health_answers_ok_and_echoes_a_correlation_id() throws Exception {
        http.perform(get("/health").header("X-Correlation-Id", "test-correlation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(header().string("X-Correlation-Id", "test-correlation"));
    }

    @Test
    void an_unknown_route_answers_with_the_envelope() throws Exception {
        http.perform(get("/api/v1/nothing")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }
}
