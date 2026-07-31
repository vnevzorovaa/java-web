package ru.ptr;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DevopsTestTaskApplicationTests {

    private static final byte[] JPEG_MAGIC_BYTES = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};

    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    void servesHtmlPageWithTheImage() throws Exception {
        long startedAt = System.nanoTime();
        HttpResponse<String> response = getAsString("/");
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(contentTypeOf(response)).startsWith("text/html");
        assertThat(response.body()).contains("<img").contains("main.jpg");
        assertThat(elapsed)
                .isGreaterThanOrEqualTo(Duration.ofSeconds(10))
                .isLessThan(Duration.ofSeconds(20));
    }

    @Test
    void servesTheImageItself() throws Exception {
        long startedAt = System.nanoTime();
        HttpResponse<byte[]> response = HTTP_CLIENT.send(
                requestTo("/main.jpg"), HttpResponse.BodyHandlers.ofByteArray());
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(contentTypeOf(response)).isEqualTo("image/jpeg");
        assertThat(response.body()).startsWith(JPEG_MAGIC_BYTES);
        assertThat(elapsed).isLessThan(Duration.ofSeconds(5));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"})
    void healthEndpointIsUpAndResponsive(String path) throws Exception {
        long startedAt = System.nanoTime();
        HttpResponse<String> response = getAsString(path);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
        assertThat(elapsed).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void exposesExactlyOneApplicationEndpoint() {
        long applicationEndpoints = handlerMapping.getHandlerMethods().values().stream()
                .filter(method -> method.getBeanType().getPackageName().startsWith("ru.ptr"))
                .count();

        assertThat(applicationEndpoints).isEqualTo(1);
    }

    private HttpResponse<String> getAsString(String path) throws IOException, InterruptedException {
        return HTTP_CLIENT.send(requestTo(path), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest requestTo(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + this.port + path))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
    }

    private static String contentTypeOf(HttpResponse<?> response) {
        return response.headers().firstValue("Content-Type").orElseThrow();
    }
}
