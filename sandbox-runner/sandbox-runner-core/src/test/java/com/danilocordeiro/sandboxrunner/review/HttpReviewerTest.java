package com.danilocordeiro.sandboxrunner.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Against a stub that speaks the ai-code-reviewer contract. */
class HttpReviewerTest {

    HttpServer server;
    final AtomicReference<String> lastBody = new AtomicReference<>();
    volatile int status = 200;
    volatile String response = "";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/review", ex -> {
            lastBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] b = response.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
            if (b.length > 0) {
                ex.getResponseBody().write(b);
            }
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    HttpReviewer reviewer() {
        return HttpReviewer.at(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/review"));
    }

    @Test
    void requestChangesBlocksAndFindingsAreParsed() {
        response = """
                {"verdict":"REQUEST_CHANGES","summary":"1 finding: 1 critical.","counts":{"CRITICAL":1},
                 "findings":[{"severity":"CRITICAL","category":"SECURITY","title":"SQL Injection","description":"d",
                 "line":1,"suggestion":"s","reviewer":"security-reviewer"}],"durationMs":7}""";
        ReviewOutcome o = reviewer().review("diff --git a/x b/x\n+bad", "t");
        assertTrue(o.blocking());
        assertEquals("SQL Injection", o.findings().getFirst().title());
        assertTrue(lastBody.get().contains("\"isDiff\":true"));
    }

    @Test
    void approveAndCommentPass() {
        response = "{\"verdict\":\"APPROVE\",\"summary\":\"ok\",\"findings\":[]}";
        assertFalse(reviewer().review("d", "t").blocking());
        response = "{\"verdict\":\"COMMENT\",\"summary\":\"nit\",\"findings\":[]}";
        assertFalse(reviewer().review("d", "t").blocking());
        assertTrue(reviewer().blockOnComment().review("d", "t").blocking());
    }

    @Test
    void failuresBlockUnlessFailOpen() {
        status = 500;
        assertTrue(reviewer().review("d", "t").blocking());
        status = 413;
        ReviewOutcome tooLarge = reviewer().review("d", "t");
        assertTrue(tooLarge.blocking());
        assertTrue(tooLarge.summary().contains("too large"));
        status = 200;
        response = "not json";
        assertTrue(reviewer().review("d", "t").blocking());
        assertFalse(reviewer().failOpen().review("d", "t").blocking());
    }

    @Test
    void refusesPlainHttpToRemoteHosts() {
        assertThrows(IllegalArgumentException.class, () -> HttpReviewer.at(URI.create("http://review.example.com/api/review")));
    }
}
