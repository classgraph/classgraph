package io.github.classgraph.issues.issue305;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

/**
 * Issue305.
 */
public class Issue305 {

    /**
     * Test that multi-line continuations in manifest file values are correctly assembled into a string.
     *
     * @throws Exception
     *             the exception
     */
    @Test
    public void issue305() throws Exception {
        ConsoleHandler errPrintStreamHandler = null;
        final Logger rootLogger = Logger.getLogger("");
        try {
            // Record log output
            final ByteArrayOutputStream err = new ByteArrayOutputStream();
            System.setErr(new PrintStream(err));
            final Logger log = Logger.getLogger(ClassGraph.class.getName());
            if (!log.isLoggable(Level.INFO)) {
                throw new Exception("Could not create log");
            }
            errPrintStreamHandler = new ConsoleHandler();
            errPrintStreamHandler.setLevel(Level.INFO);
            rootLogger.addHandler(errPrintStreamHandler);

            try (ScanResult scanResult = new ClassGraph()
                    .overrideClassLoaders(new URLClassLoader(new URL[] {
                            Issue305.class.getClassLoader().getResource("class-path-manifest-entry.jar") }))
                    // This .verbose() is needed (stderr is captured)
                    .verbose().scan()) {
            }

            final String systemErrMessages = new String(err.toByteArray());
            assertThat(systemErrMessages.indexOf("Found Class-Path entry in manifest file: "
                    + "file:/C:/Program%20Files/Java/jdk1.8.0_162/jre/lib/charsets.jar "
                    + "file:/C:/Program%20Files/Java/jdk1.8.0_162/jre/lib/deploy.jar "
                    + "file:/C:/Program%20Files/Java/jdk1.8.0_162/jre/lib/ext/access-bridge-64.jar "
                    + "file:/C:/Program%20Files/Java/jdk1.8.0_162/jre/lib/ext/cldrdata.jar "
                    + "file:/C:/Program%20Files/Java/jdk1.8.0_162/jre/lib/ext/dnsns.jar "
                    + "file:/C:/Program%20Files/Java/jdk1.8.0_162/jre/lib/ext/jaccess.jar "
                    + "file:/C:/Program%20Files/Java/jdk1.8.0_162/jre/lib/ext/jfxrt.jar "
                    + "file:/C:/Program%20Files/Java/jdk1.8.0_162/jre/lib/ext/localedata.jar "
                    + "file:/C:/Program%20Files/Java/jdk1.8.0_162/jre/lib/ext/nashorn.jar "
                    + "file:/C:/Program%20Files/Java/jdk1.8.0_162/jre/lib/ext/sunec.jar "
                    + "file:/C:/Program%20Files/Java/jdk1.8.0_162/jre/lib/ext/sunjce_provider.jar "
                    + "file:/C:/Program%20Files/Java/jdk1.8.0_162/jre/lib/ext/sunmscapi.jar "
                    + "file:/C:/Program%20Files/Java/jdk1.8.0_162/jre/lib/ext/sunpkcs11.jar "
                    + "file:/C:/Program%20Files/Java/jdk1.8.0_162/jre/lib/ext/zipfs.jar "
                    + "file:/C:/Program%20Files/Java/jdk1.8.0_162/jre/lib/javaws.jar "
                    + "file:/C:/Program%20Files/Java/jdk1.8.0_162/jre/lib/jce.jar "
                    + "file:/C:/Program%20Files/Java/jdk1.8.0_162/jre/lib/jfr.jar "
                    + "file:/C:/Program%20Files/Java/jdk1.8.0_162/jre/lib/jfxswt.jar "
                    + "file:/C:/Program%20Files/Java/jdk1.8.0_162/jre/lib/jsse.jar "
                    + "file:/C:/Program%20Files/Java/jdk1.8.0_162/jre/lib/management-agent.jar "
                    + "file:/C:/Program%20Files/Java/jdk1.8.0_162/jre/lib/plugin.jar "
                    + "file:/C:/Program%20Files/Java/jdk1.8.0_162/jre/lib/resources.jar "
                    + "file:/C:/Program%20Files/Java/jdk1.8.0_162/jre/lib/rt.jar "
                    + "file:/Z:/classgraphtest/target/classes/ "
                    + "file:/C:/Users/flame/.m2/repository/io/github/classgraph/classgraph/4.6.19/classgraph-4.6.19.jar "
                    + "file:/C:/Program%20Files/JetBrains/IntelliJ%20IDEA%20Community%20Edition%202018.2.1/lib/idea_rt.jar"))
                            .isGreaterThan(0);

        } finally {
            rootLogger.removeHandler(errPrintStreamHandler);
            // Set to System.err
            System.setErr(System.err);
        }
    }
}
