package pl.aiwcorpo.mcp.platform;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;

/**
 * A copy of what a {@link ListAppender} has collected. The appender adds events under its own monitor from the
 * threads that log (Tomcat, the SDK client keep logging after a response is sent), while its list is a plain
 * ArrayList: reading it from the test thread without that monitor throws ConcurrentModificationException now and then.
 */
public final class CapturedLogs {

    private CapturedLogs() {
    }

    public static List<ILoggingEvent> snapshot(ListAppender<ILoggingEvent> appender) {
        synchronized (appender) {
            return List.copyOf(appender.list);
        }
    }
}
