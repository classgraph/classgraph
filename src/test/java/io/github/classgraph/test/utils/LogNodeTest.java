package io.github.classgraph.test.utils;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
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
}
