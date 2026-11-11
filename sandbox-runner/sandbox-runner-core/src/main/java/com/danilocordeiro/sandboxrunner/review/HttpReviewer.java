package com.danilocordeiro.sandboxrunner.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Calls a review service with the ai-code-reviewer contract:
 * {@code POST /api/review {"fileName", "content", "isDiff": true}} returning
 * {@code {"verdict", "summary", "findings": [...]}}.
 *
 * <p>{@code REQUEST_CHANGES} blocks. Any failure to get a verdict (timeout,
 * 413 too large, 5xx, unparseable body) also blocks unless {@link #failOpen()}
 * is set: a missing review never reads as an approval.
 */
public final class HttpReviewer implements Reviewer {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final URI endpoint;
    private final Duration timeout;
    private final boolean failOpen;
    private final boolean blockOnComment;
    private final HttpClient http;

    private HttpReviewer(URI endpoint, Duration timeout, boolean failOpen, boolean blockOnComment) {
        String scheme = endpoint.getScheme();
        String host = endpoint.getHost();
        boolean local = "localhost".equals(host) || "127.0.0.1".equals(host) || "::1".equals(host);
        if (!"https".equals(scheme) && !("http".equals(scheme) && local)) {
            throw new IllegalArgumentException("reviewer must be https, or http on localhost: diffs can contain sensitive code");
        }
        this.endpoint = endpoint;
        this.timeout = timeout;
        this.failOpen = failOpen;
        this.blockOnComment = blockOnComment;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** {@code uri} is the full endpoint, e.g. {@code http://localhost:8080/api/review}. */
    public static HttpReviewer at(URI uri) {
        return new HttpReviewer(uri, Duration.ofMinutes(4), false, false);
    }

    public HttpReviewer timeout(Duration t) {
        return new HttpReviewer(endpoint, t, failOpen, blockOnComment);
    }

    /** Let the run continue when no verdict could be obtained. Not recommended. */
    public HttpReviewer failOpen() {
        return new HttpReviewer(endpoint, timeout, true, blockOnComment);
    }

    /** Treat COMMENT as blocking too. */
    public HttpReviewer blockOnComment() {
        return new HttpReviewer(endpoint, timeout, failOpen, true);
    }

    @Override
    public ReviewOutcome review(String diff, String title) {
        try {
            String body = JSON.writeValueAsString(Map.of("fileName", "sandbox-run.diff", "content", diff, "isDiff", true));
            HttpRequest req = HttpRequest.newBuilder(endpoint).timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                return unavailable("reviewer returned HTTP " + res.statusCode() + (res.statusCode() == 413 ? " (diff too large)" : ""));
            }
            JsonNode n = JSON.readTree(res.body());
            String verdict = n.path("verdict").asText("");
            if (!List.of("APPROVE", "COMMENT", "REQUEST_CHANGES").contains(verdict)) {
                return unavailable("reviewer returned no known verdict");
            }
            List<Finding> findings = new ArrayList<>();
            for (JsonNode f : n.path("findings")) {
                findings.add(JSON.treeToValue(f, Finding.class));
            }
            boolean blocking = verdict.equals("REQUEST_CHANGES") || (blockOnComment && verdict.equals("COMMENT"));
            return new ReviewOutcome(verdict, blocking, n.path("summary").asText(""), findings, Optional.empty());
        } catch (IOException e) {
            return unavailable("reviewer call failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return unavailable("interrupted");
        }
    }

    private ReviewOutcome unavailable(String why) {
        return new ReviewOutcome("UNAVAILABLE", !failOpen, why, List.of(), Optional.of(why));
    }
}
