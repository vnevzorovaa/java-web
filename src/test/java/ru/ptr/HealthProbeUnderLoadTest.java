package ru.ptr;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The page endpoint keeps its request thread busy for the whole response. On a small platform
 * thread pool that starves the health endpoints, which would make a Kubernetes liveness probe
 * fail under load. Virtual threads (spring.threads.virtual.enabled) make server.tomcat.threads.max
 * irrelevant, so this test pins that behaviour: it fails if virtual threads are turned off.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.tomcat.threads.max=5")
class HealthProbeUnderLoadTest {

    private static final int CONCURRENT_SLOW_REQUESTS = 20;

    @LocalServerPort
    private int port;

    @Test
    void livenessProbeAnswersWhileManySlowRequestsAreInFlight() throws Exception {
        HttpClient slowCallers = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        List<CompletableFuture<HttpResponse<Void>>> inFlight = IntStream.range(0, CONCURRENT_SLOW_REQUESTS)
                .mapToObj(i -> slowCallers.sendAsync(requestTo("/"), HttpResponse.BodyHandlers.discarding()))
                .toList();
        Thread.sleep(Duration.ofSeconds(2));

        // A dedicated client, so the probe cannot queue behind the slow calls on the client side.
        HttpClient probeCaller = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        long startedAt = System.nanoTime();
        HttpResponse<String> probe = probeCaller.send(
                requestTo("/actuator/health/liveness"), HttpResponse.BodyHandlers.ofString());
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(probe.statusCode()).isEqualTo(200);
        assertThat(probe.body()).contains("\"status\":\"UP\"");
        assertThat(elapsed).isLessThan(Duration.ofSeconds(3));

        CompletableFuture.allOf(inFlight.toArray(CompletableFuture[]::new)).join();
        assertThat(inFlight).allSatisfy(request -> assertThat(request.join().statusCode()).isEqualTo(200));
    }

    private HttpRequest requestTo(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + this.port + path))
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();
    }
}
