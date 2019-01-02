package io.github.classgraph.test.utils;

import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.github.classgraph.ClassGraph;
import nonapi.io.github.classgraph.utils.LogNode;
import org.junit.Test;

public class LogNodeTest {

    @Test
    public void testLogNodeLogging() {
        // set the System.err
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        System.setErr(new PrintStream(err));

        System.setProperty("java.util.logging.config.file",
                "./src/test/resources/logging.properties");
        LogNode node = new LogNode();
        node.log("any logging message").log("child message").log("sub child message");
        node.log("another root");
        node.flush();

        Logger log = Logger.getLogger(ClassGraph.class.getName());
        if (log.isLoggable(Level.INFO)) {
            String systemErrMessages = new String(err.toByteArray());
            assertTrue(systemErrMessages.contains("any logging message"));
            assertTrue(systemErrMessages.contains("-- child message"));
            assertTrue(systemErrMessages.contains("---- sub child message"));
            assertTrue(systemErrMessages.contains("another root"));
            System.out.println(systemErrMessages);
        } // else logging will not take place
    }

}
