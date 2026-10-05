package co.edu.corhuila.barbersaas.workflow.adapter.in.http;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Liveness probe for barber-saas-infra. Outside /api/, so it needs no token. */
@RestController
public class HealthController {

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }
}
