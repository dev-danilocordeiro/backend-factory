package com.danilocordeiro.sandboxrunner.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs a host process with a timeout and bounded output capture. Output beyond
 * {@code maxBytes} per stream keeps only the tail, so a chatty process cannot
 * exhaust the JVM heap.
 */
public final class Proc {

    /** Outcome of a process run. */
    public record Result(int exitCode, String stdout, String stderr, boolean timedOut) {
        public boolean ok() {
            return exitCode == 0 && !timedOut;
        }

        /** stdout and stderr together, for error messages. */
        public String output() {
            return stderr.isBlank() ? stdout : stdout + (stdout.isEmpty() ? "" : "\n") + stderr;
        }
    }

    public static final int DEFAULT_MAX_BYTES = 1 << 20;

    private Proc() {
    }

    public static Result run(List<String> command, Path dir, Map<String, String> env, Duration timeout) {
        return run(command, dir, env, timeout, null, DEFAULT_MAX_BYTES);
    }

    public static Result run(List<String> command, Path dir, Map<String, String> env, Duration timeout,
                             byte[] stdin, int maxBytes) {
        ProcessBuilder pb = new ProcessBuilder(command);
        if (dir != null) {
            pb.directory(dir.toFile());
        }
        pb.environment().putAll(env);
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot start " + command.getFirst(), e);
        }
        TailBuffer out = new TailBuffer(maxBytes);
        TailBuffer err = new TailBuffer(maxBytes);
        Thread to = Thread.ofVirtual().start(() -> pump(p.getInputStream(), out));
        Thread te = Thread.ofVirtual().start(() -> pump(p.getErrorStream(), err));
        try {
            try (var in = p.getOutputStream()) {
                if (stdin != null) {
                    in.write(stdin);
                }
            } catch (IOException ignored) {
                // process may exit without reading stdin
            }
            boolean done = p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!done) {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
                p.waitFor(10, TimeUnit.SECONDS);
            }
            to.join(Duration.ofSeconds(10));
            te.join(Duration.ofSeconds(10));
            return new Result(done ? p.exitValue() : -1, out.text(), err.text(), !done);
        } catch (InterruptedException e) {
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            return new Result(-1, out.text(), err.text(), true);
        }
    }

    private static void pump(InputStream in, TailBuffer buf) {
        byte[] chunk = new byte[8192];
        try (in) {
            int n;
            while ((n = in.read(chunk)) > 0) {
                buf.write(chunk, n);
            }
        } catch (IOException ignored) {
            // stream closed when the process is killed
        }
    }

    /** Keeps the last maxBytes bytes written. */
    static final class TailBuffer {
        private final int max;
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        private boolean truncated;

        TailBuffer(int max) {
            this.max = max;
        }

        synchronized void write(byte[] b, int n) {
            buf.write(b, 0, n);
            if (buf.size() > 2 * max) {
                byte[] all = buf.toByteArray();
                buf.reset();
                buf.write(all, all.length - max, max);
                truncated = true;
            }
        }

        synchronized String text() {
            byte[] all = buf.toByteArray();
            if (all.length > max) {
                truncated = true;
                byte[] tail = new byte[max];
                System.arraycopy(all, all.length - max, tail, 0, max);
                all = tail;
            }
            String s = new String(all, StandardCharsets.UTF_8);
            return truncated ? "[…truncated…]\n" + s : s;
        }
    }
}
