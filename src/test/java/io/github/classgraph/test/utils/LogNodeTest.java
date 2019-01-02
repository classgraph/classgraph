package io.github.classgraph.test.utils;

import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.junit.Test;

import io.github.classgraph.ClassGraph;
import nonapi.io.github.classgraph.utils.LogNode;

public class LogNodeTest {

    @Test
    public void testLogNodeLogging() {
        // set the System.err
        final ByteArrayOutputStream err = new ByteArrayOutputStream();
        System.setErr(new PrintStream(err));

        System.setProperty("java.util.logging.config.file", "./src/test/resources/logging.properties");
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
