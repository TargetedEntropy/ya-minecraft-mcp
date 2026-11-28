package dev.yamc.mcp.debug;

import com.google.gson.JsonObject;
import dev.yamc.mcp.YamcMcp;
import dev.yamc.mcp.client.EventLog;
import dev.yamc.mcp.client.GameEvent;
import dev.yamc.mcp.platform.Settings;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.impl.Log4jContextFactory;

import java.util.IdentityHashMap;
import java.util.Set;
import java.util.Collections;

/**
 * Copies the game's log (INFO and above, from every logger that reaches the root logger) into {@link #BUFFER}, and
 * records each ERROR as a {@code log_error} event. Installed once at startup; anything logged before that is missed.
 */
public final class LogCapture {
    private LogCapture() {}

    private static final int MAX_MESSAGE = 4000;

    public static final LogBuffer BUFFER = new LogBuffer(LogCapture::capacity);

    /** Set while this thread is recording a line: whatever that logs itself is not recorded again. */
    private static final ThreadLocal<Boolean> RECORDING = ThreadLocal.withInitial(() -> false);
    private static boolean installed;

    /** Settings are not readable until the loader has loaded the config: use the default until then. */
    private static int capacity() {
        try {
            return Settings.LOG_BUFFER_SIZE.get();
        } catch (RuntimeException e) {
            return Settings.LOG_BUFFER_SIZE.def();
        }
    }

    public static boolean installed() {
        return installed;
    }

    public static synchronized void install() {
        if (installed) return;
        try {
            Set<LoggerContext> contexts = Collections.newSetFromMap(new IdentityHashMap<>());
            if (LogManager.getFactory() instanceof Log4jContextFactory f) contexts.addAll(f.getSelector().getLoggerContexts());
            if (LogManager.getContext(false) instanceof LoggerContext c) contexts.add(c);
            for (LoggerContext ctx : contexts) {
                Appender a = new Appender();
                a.start();
                ctx.getConfiguration().getRootLogger().addAppender(a, org.apache.logging.log4j.Level.INFO, null);
                ctx.updateLoggers();
            }
            installed = !contexts.isEmpty();
        } catch (Throwable t) {
            YamcMcp.LOGGER.warn("Log capture unavailable (logs/exceptions tools will be empty): {}", t.toString());
        }
    }

    private static final class Appender extends AbstractAppender {
        Appender() {
            super("yamcmcp-capture", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent e) {
            if (RECORDING.get()) return;
            RECORDING.set(true);
            try {
                LogBuffer.Level level = LogBuffer.Level.parse(e.getLevel().name());
                if (level == null) level = LogBuffer.Level.INFO;
                String msg = e.getMessage() == null ? "" : e.getMessage().getFormattedMessage();
                if (msg.length() > MAX_MESSAGE) msg = msg.substring(0, MAX_MESSAGE) + "...";
                LogBuffer.Line line = BUFFER.add(e.getTimeMillis(), level, String.valueOf(e.getLoggerName()),
                        String.valueOf(e.getThreadName()), msg, LogBuffer.Thrown.of(e.getThrown()));
                if (level.compareTo(LogBuffer.Level.ERROR) >= 0) event(line);
            } catch (Throwable ignored) {
                // never let logging fail because of us
            } finally {
                RECORDING.set(false);
            }
        }
    }

    private static void event(LogBuffer.Line l) {
        JsonObject d = new JsonObject();
        d.addProperty("log_seq", l.seq());
        d.addProperty("level", l.level().name());
        d.addProperty("logger", l.logger());
        d.addProperty("message", l.message().length() > 300 ? l.message().substring(0, 300) + "..." : l.message());
        if (l.thrown() != null) d.addProperty("exception", l.thrown().headline());
        try {
            EventLog.add(GameEvent.LOG_ERROR, d);
        } catch (RuntimeException ignored) {
            // config not loaded yet (very early startup): the line is still in the buffer
        }
    }
}
