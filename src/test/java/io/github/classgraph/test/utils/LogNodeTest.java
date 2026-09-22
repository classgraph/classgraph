package io.github.classgraph.test.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import nonapi.io.github.classgraph.utils.LogNode;

/**
 * LogNodeTest.
 */
public class LogNodeTest {
    private ConsoleHandler errPrintStreamHandler = null;
    private final Logger rootLogger = Logger.getLogger("");

    /** Reset encapsulation circumvention method after each test. */
    @AfterEach
    void resetAfterTest() {
        rootLogger.removeHandler(errPrintStreamHandler);
        // Set to System.err
        System.setErr(System.err);
    }

    /**
     * Test log node logging to system err.
     */
    @Test
    public void testLogNodeLoggingToSystemErr() {
        // Set the System.err
        final ByteArrayOutputStream err = new ByteArrayOutputStream();
        System.setErr(new PrintStream(err));

        errPrintStreamHandler = new ConsoleHandler();
        errPrintStreamHandler.setLevel(Level.INFO);
        rootLogger.addHandler(errPrintStreamHandler);

        final LogNode node = new LogNode();
        node.log("any logging message").log("child message").log("sub child message");
        node.log("another root");
        node.flush();

        final Logger log = Logger.getLogger(ClassGraph.class.getName());
        if (log.isLoggable(Level.INFO)) {
            final String systemErrMessages = new String(err.toByteArray());
            assertTrue(systemErrMessages.contains("any logging message"));
            assertTrue(systemErrMessages.contains("-- child message"));
            assertTrue(systemErrMessages.contains("---- sub child message"));
            assertTrue(systemErrMessages.contains("another root"));
            // System.out.println(systemErrMessages);
        } // else logging will not take place
    }

    /** An entry written in realtime is indented by its depth in the tree, as it is when the tree is written out. */
    @Test
    public void realtimeLoggingIndentsNestedEntries() {
        final List<String> logged = new ArrayList<>();
        final Handler handler = new Handler() {
            @Override
            public void publish(final LogRecord record) {
                logged.add(record.getMessage());
            }

            @Override
            public void flush() {
                // Nothing to flush
            }

            @Override
            public void close() {
                // Nothing to close
            }
        };
        final Logger log = Logger.getLogger(ClassGraph.class.getName());
        final boolean useParentHandlers = log.getUseParentHandlers();
        log.setUseParentHandlers(false);
        log.addHandler(handler);
        final LogNode node = new LogNode();
        LogNode.logInRealtime(true);
        try {
            node.log("outer").log("inner");
        } finally {
            LogNode.logInRealtime(false);
            log.removeHandler(handler);
            log.setUseParentHandlers(useParentHandlers);
        }
        assertEquals(2, logged.size());
        assertTrue(logged.get(0).contains("\touter\n"), logged.get(0));
        assertTrue(logged.get(1).contains("\t-- inner\n"), logged.get(1));
    }
}
