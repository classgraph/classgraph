package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * A classfile that is rejected is not scanned as a class, but it is still one of the files of the classpath
 * element, so it is still listed as a resource -- and, since it is listed, the verbose log has to say that it was.
 */
public class RejectedClassfileResourceTest {
    /** The logger that the verbose log is written to. */
    private static final Logger LOGGER = Logger.getLogger("io.github.classgraph.ClassGraph");

    /** A class that is accepted by the scan. */
    public static class Accepted {
    }

    /** A class that is rejected by the scan, and so is only found as a resource. */
    public static class Rejected {
    }

    /** The path of the classfile of the rejected class. */
    private static final String REJECTED_CLASSFILE_PATH = Rejected.class.getName().replace('.', '/') + ".class";

    /** The verbose log of the scan. */
    private static String log;

    /** The paths of the resources the scan found. */
    private static java.util.List<String> resourcePaths;

    /** Scan with one class rejected, recording the verbose log rather than printing it. */
    @BeforeAll
    static void scanWithARejectedClass() {
        final var logged = new StringBuilder();
        final var handler = new Handler() {
            @Override
            public void publish(final LogRecord record) {
                logged.append(record.getMessage()).append('\n');
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
        final var useParentHandlers = LOGGER.getUseParentHandlers();
        LOGGER.setUseParentHandlers(false);
        LOGGER.addHandler(handler);
        try (var scanResult = new ClassGraph().enableClassInfo().enableClasspath()
                .acceptPackages(RejectedClassfileResourceTest.class.getPackageName())
                .rejectClasses(Rejected.class.getName()).verbose().scan()) {
            resourcePaths = scanResult.getAllResources().getPaths();
            assertThat(scanResult.getAllClasses().getNames()).contains(Accepted.class.getName())
                    .doesNotContain(Rejected.class.getName());
        } finally {
            LOGGER.removeHandler(handler);
            LOGGER.setUseParentHandlers(useParentHandlers);
        }
        log = logged.toString();
    }

    /** The classfile of a rejected class is still listed as a resource. */
    @Test
    public void aRejectedClassfileIsStillListedAsAResource() {
        assertThat(resourcePaths).contains(REJECTED_CLASSFILE_PATH);
    }

    /** The verbose log reports every resource that was listed, including the classfile of a rejected class. */
    @Test
    public void theVerboseLogReportsARejectedClassfileThatWasListedAsAResource() {
        assertThat(log).contains("Found resource within accepted package: " + REJECTED_CLASSFILE_PATH);
    }
}
