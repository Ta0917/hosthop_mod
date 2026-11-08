package dev.hosthop.world;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

/** Conservative additional signal: any error or save-related warning blocks publication. */
public final class SaveFailureMonitor implements AutoCloseable {
    private final AtomicBoolean failed = new AtomicBoolean();
    private final LoggerContext context;
    private final AbstractAppender appender;

    public SaveFailureMonitor() {
        context = (LoggerContext) LogManager.getContext(false);
        appender = new AbstractAppender("HostHopSaveMonitor-" + java.util.UUID.randomUUID(), null, null, false, Property.EMPTY_ARRAY) {
            @Override public void append(LogEvent event) {
                if (event.getLevel().isMoreSpecificThan(Level.ERROR)) { failed.set(true); return; }
                if (event.getLevel().isMoreSpecificThan(Level.WARN)) {
                    String message = event.getMessage().getFormattedMessage().toLowerCase(Locale.ROOT);
                    if ((message.contains("fail") || message.contains("error") || message.contains("could not") || message.contains("couldn't"))
                            && (message.contains("sav") || message.contains("writ") || message.contains("chunk")
                                || message.contains("world") || message.contains("player") || message.contains("lock"))) failed.set(true);
                }
            }
        };
        appender.start();
        context.getConfiguration().getRootLogger().addAppender(appender, Level.WARN, null);
        context.updateLoggers();
    }
    public boolean hasFailure() { return failed.get(); }
    @Override public void close() {
        context.getConfiguration().getRootLogger().removeAppender(appender.getName());
        context.updateLoggers();
        appender.stop();
    }
}
