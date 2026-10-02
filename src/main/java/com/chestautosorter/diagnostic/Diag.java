package com.chestautosorter.diagnostic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

/**
 * Structured, non-spammy diagnostic log. One line per phase per request, keyed by a request id.
 * Inventory contents are only emitted when debug is enabled.
 */
public final class Diag {
    private static final Logger LOG = LoggerFactory.getLogger("ChestAutoSorter");
    private static final int HISTORY = 32;
    private static final Deque<String> RECENT = new ArrayDeque<>();
    private static volatile boolean debug = false;
    private static volatile boolean trace = false;

    private Diag() {}

    public static void setDebug(boolean d) {
        debug = d;
    }

    public static void setTrace(boolean t) {
        trace = t;
    }

    public static boolean isDebug() {
        return debug;
    }

    public static synchronized void record(String line) {
        RECENT.addLast(line);
        while (RECENT.size() > HISTORY) {
            RECENT.removeFirst();
        }
    }

    public static synchronized String[] recent() {
        return RECENT.toArray(new String[0]);
    }

    public static void info(String line) {
        LOG.info(line);
    }

    public static void warn(String line) {
        LOG.warn(line);
    }

    public static void error(String line) {
        LOG.error(line);
    }

    public static void debug(String line) {
        if (debug) {
            LOG.info("[debug] " + line);
        }
    }

    public static void trace(String line) {
        if (trace) {
            LOG.info("[trace] " + line);
        }
    }

    public static void phase(UUID requestId, String phase, String detail) {
        String line = "req=" + shortId(requestId) + " phase=" + phase + " " + detail;
        LOG.info(line);
        record(line);
    }

    public static void phaseDebug(UUID requestId, String phase, String detail) {
        if (debug || trace) {
            phase(requestId, phase, detail);
        }
    }

    private static String shortId(UUID id) {
        return id == null ? "----" : id.toString().substring(0, 8);
    }
}
