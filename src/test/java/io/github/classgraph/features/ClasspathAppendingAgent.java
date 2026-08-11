package io.github.classgraph.features;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.util.jar.JarFile;

/**
 * A Java agent that appends a jar to the system classloader's search path, which is the only way an application can
 * add a classpath entry that is not listed in the {@code java.class.path} system property.
 */
public final class ClasspathAppendingAgent {
    /** Cannot be instantiated. */
    private ClasspathAppendingAgent() {
    }

    /**
     * Append the jar named by the agent arguments to the system classloader's search path.
     *
     * @param agentArgs
     *            the path of the jar to append.
     * @param instrumentation
     *            the instrumentation instance provided by the JVM.
     * @throws IOException
     *             if the jar could not be opened.
     */
    public static void premain(final String agentArgs, final Instrumentation instrumentation) throws IOException {
        // The JarFile is deliberately not closed -- the system classloader reads from it for the rest of the
        // lifetime of the JVM
        instrumentation.appendToSystemClassLoaderSearch(new JarFile(agentArgs));
    }
}
