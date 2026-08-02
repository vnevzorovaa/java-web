package ru.ptr.web;

import java.time.Duration;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The single API of the application: an HTML page with an image.
 */
@RestController
public class PageController {

    /** Intentionally not configurable. */
    private static final Duration RESPONSE_DELAY = Duration.ofSeconds(12);

    private static final Resource PAGE = new ClassPathResource("web/index.html");

    @GetMapping(path = "/", produces = "text/html;charset=UTF-8")
    public Resource page() {
        try {
            Thread.sleep(RESPONSE_DELAY);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Request was interrupted", ex);
        }
        return PAGE;
    }
}
