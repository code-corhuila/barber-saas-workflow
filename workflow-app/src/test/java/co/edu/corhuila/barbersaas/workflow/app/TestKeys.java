package co.edu.corhuila.barbersaas.workflow.app;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.springframework.test.context.DynamicPropertyRegistry;

/** One RS256 key pair for the tests: the public half configures the service, the private one signs tokens. */
final class TestKeys {

    private static final KeyPair KEYS = generate();

    private TestKeys() { }

    static void register(DynamicPropertyRegistry registry) {
        String pem = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(KEYS.getPublic().getEncoded()) + "\n-----END PUBLIC KEY-----\n";
        registry.add("JWT_PUBLIC_KEY", () -> pem);
        registry.add("SERVICE_TOKEN", () -> "workflow-service-token");
    }

    /** A token with the claims of 07-api/authentication.md. */
    static String token(String subject, String role, UUID barbershopId) throws Exception {
        long now = Instant.now().getEpochSecond();
        String tenant = barbershopId == null ? "" : ",\"barbershopId\":\"" + barbershopId + "\"";
        String header = b64("{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"dev-1\"}");
        String claims = b64("{\"iss\":\"barber-saas-identity-auth-api\",\"sub\":\"" + subject + "\",\"role\":\"" + role
                + "\",\"iat\":" + now + ",\"exp\":" + (now + 600) + tenant + "}");
        Signature rsa = Signature.getInstance("SHA256withRSA");
        rsa.initSign(KEYS.getPrivate());
        rsa.update((header + "." + claims).getBytes(StandardCharsets.US_ASCII));
        return header + "." + claims + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(rsa.sign());
    }

    private static String b64(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
