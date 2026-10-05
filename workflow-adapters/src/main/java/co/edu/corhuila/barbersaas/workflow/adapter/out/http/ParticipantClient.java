package co.edu.corhuila.barbersaas.workflow.adapter.out.http;

import co.edu.corhuila.barbersaas.workflow.application.port.out.Participants.StepUnavailable;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Calls a participant the way annex E asks (DEC-WF-04): the workflow's own service token, the
 * request's X-Correlation-Id, a timeout per attempt, and bounded retries with jitter only on a
 * network error, 429 or 5xx. Any other answer is returned for the caller to read.
 */
public class ParticipantClient {

    private static final Logger log = LoggerFactory.getLogger(ParticipantClient.class);

    private final HttpClient http;
    private final String serviceToken;
    private final Duration timeout;
    private final int maxAttempts;
    private final Duration backoff;

    public ParticipantClient(HttpClient http, String serviceToken, Duration timeout, int maxAttempts,
                             Duration backoff) {
        if (serviceToken == null || serviceToken.isBlank()) {
            throw new IllegalArgumentException("SERVICE_TOKEN is required to call the participants");
        }
        this.http = http;
        this.serviceToken = serviceToken;
        this.timeout = timeout;
        this.maxAttempts = maxAttempts;
        this.backoff = backoff;
    }

    public HttpResponse<String> send(HttpRequest.Builder request, String what) {
        request.timeout(timeout).header("Authorization", "Bearer " + serviceToken);
        String correlationId = MDC.get("correlationId");
        if (correlationId != null) {
            request.header("X-Correlation-Id", correlationId);
        }
        HttpRequest built = request.build();
        String last = "no attempt";
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                HttpResponse<String> response = http.send(built, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status != 429 && status < 500) {
                    return response;
                }
                last = what + " answered " + status;
            } catch (IOException e) {
                last = what + " unreachable: " + e.getClass().getSimpleName();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new StepUnavailable(what + " interrupted");
            }
            log.warn("participant call failed attempt={} of={} reason={}", attempt, maxAttempts, last);
            if (attempt < maxAttempts) {
                pause(attempt);
            }
        }
        throw new StepUnavailable(last + " after " + maxAttempts + " attempts");
    }

    /** Exponential backoff, each wait a random point in its upper half, so retries do not all land together. */
    private void pause(int attempt) {
        long ceiling = backoff.toMillis() << (attempt - 1);
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(ceiling / 2, ceiling + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
