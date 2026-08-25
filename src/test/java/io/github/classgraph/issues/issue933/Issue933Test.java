package io.github.classgraph.issues.issue933;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

/**
 * A scan started from a static initializer cannot load classes on a worker thread: the calling thread holds the
 * initialization lock of the class being initialized, and a classloader that takes a lock of its own then deadlocks
 * against it. The deadlock cannot be broken, since a thread that is blocked on class loading cannot be interrupted,
 * so the scan falls back to running on the calling thread instead (#933).
 */
public class Issue933Test {
    /** The logger that the verbose log is written to. */
    private static final Logger LOGGER = Logger.getLogger("io.github.classgraph.ClassGraph");

    /** A class that scans from its own static initializer, which is what the fallback exists for. */
    private static class ScansFromAStaticInitializer {
        static {
            try (ScanResult scanResult = new ClassGraph()
                    .acceptPackages(Issue933Test.class.getPackage().getName()).verbose().scan()) {
                assertThat(scanResult.getAllResources()).isNotNull();
            }
        }

        /** Force this class to be initialized, so that its static initializer runs. */
        static void initialize() {
            // The class is initialized by the call itself
        }
    }

    /**
     * Run something that scans, recording the verbose log of the scan rather than printing it.
     *
     * @param runnable
     *            the code to run.
     * @return the verbose log written while it ran.
     */
    private static String recordVerboseLog(final Runnable runnable) {
        final StringBuilder logged = new StringBuilder();
        final Handler handler = new Handler() {
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
        final boolean useParentHandlers = LOGGER.getUseParentHandlers();
        LOGGER.setUseParentHandlers(false);
        LOGGER.addHandler(handler);
        try {
            runnable.run();
        } finally {
            LOGGER.removeHandler(handler);
            LOGGER.setUseParentHandlers(useParentHandlers);
        }
        return logged.toString();
    }

    /**
     * A classloader that scans while it is loading a class. Most classloaders hold a lock of their own while
     * loading, and the JVM holds that classloader's loading lock for the name being loaded, so this is the other
     * way a caller can be holding a lock that the classloader also needs.
     */
    private static class ScansWhileLoadingAClass extends ClassLoader {
        /** The log of the scan run by {@link #loadClass(String, boolean)}. */
        String scanLog = "";

        ScansWhileLoadingAClass() {
            super(Issue933Test.class.getClassLoader());
        }

        @Override
        protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
            if (name.equals(Issue933Test.class.getName())) {
                scanLog = recordVerboseLog(new Runnable() {
                    @Override
                    public void run() {
                        try (ScanResult scanResult = new ClassGraph()
                                .acceptPackages(Issue933Test.class.getPackage().getName()).verbose().scan()) {
                            assertThat(scanResult.getAllResources()).isNotNull();
                        }
                    }
                });
            }
            return super.loadClass(name, resolve);
        }
    }

    /** A scan started from a classloader that is loading a class runs on the calling thread, and says so. */
    @Test
    public void aScanStartedByAClassLoaderThatIsLoadingAClassRunsOnTheCallingThread() throws Exception {
        final ScansWhileLoadingAClass classLoader = new ScansWhileLoadingAClass();
        classLoader.loadClass(Issue933Test.class.getName());
        assertThat(classLoader.scanLog).contains("The thread that called scan() is holding a class loading lock in "
                + ScansWhileLoadingAClass.class.getName() + ".loadClass");
        assertThat(classLoader.scanLog).contains("running the whole scan on the calling thread");
        assertThat(classLoader.scanLog).contains("Number of worker threads: 1");
    }

    /** A scan started from a static initializer runs on the calling thread, and says so in the log. */
    @Test
    public void aScanStartedFromAStaticInitializerRunsOnTheCallingThread() {
        final String log = recordVerboseLog(new Runnable() {
            @Override
            public void run() {
                ScansFromAStaticInitializer.initialize();
            }
        });
        assertThat(log).contains("The thread that called scan() is holding a class loading lock in "
                + ScansFromAStaticInitializer.class.getName() + ".<clinit>");
        assertThat(log).contains("running the whole scan on the calling thread");
        // The fallback is what the rest of the scan then reports, rather than the requested number of threads
        assertThat(log).contains("Number of worker threads: 1");
    }
}
