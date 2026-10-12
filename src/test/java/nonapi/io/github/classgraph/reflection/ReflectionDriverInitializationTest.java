package nonapi.io.github.classgraph.reflection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URL;
import java.net.URLClassLoader;

import org.junit.jupiter.api.Test;

/** Fatal initialization failures must not silently change the reflection implementation. */
public class ReflectionDriverInitializationTest {
    /**
     * Construct the utility with isolated configuration and a synthetic lookup failure.
     *
     * @param failedClass
     *            the class lookup that should fail.
     * @param failure
     *            the error thrown without actually exhausting VM resources.
     * @return the selected driver's name when initialization succeeds.
     * @throws Exception
     *             if construction or classloader closure fails.
     */
    private static String initializeWithFailure(final String failedClass, final Error failure) throws Exception {
        final URL location = ReflectionUtils.class.getProtectionDomain().getCodeSource().getLocation();
        try (URLClassLoader loader = new URLClassLoader(new URL[] { location }, null) {
            @Override
            protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
                if (name.equals(failedClass)) {
                    throw failure;
                }
                return super.loadClass(name, resolve);
            }
        }) {
            final Class<?> classGraph = Class.forName("io.github.classgraph.ClassGraph", true, loader);
            final java.lang.reflect.Field config = classGraph.getField("CIRCUMVENT_ENCAPSULATION");
            for (final Object option : config.getType().getEnumConstants()) {
                if (((Enum<?>) option).name().equals("NARCISSUS")) {
                    config.set(null, option);
                }
            }
            final Class<?> utility = Class.forName(ReflectionUtils.class.getName(), true, loader);
            final Object instance = utility.getConstructor().newInstance();
            return utility.getField("reflectionDriver").get(instance).getClass().getSimpleName();
        }
    }

    /** Both optional bootstrap paths propagate fatal errors by identity. */
    @Test
    public void fatalInitializationErrorsAreRethrown() {
        for (final String name : new String[] { "io.github.toolfactory.narcissus.Narcissus",
                "java.security.AccessController" }) {
            for (final VirtualMachineError failure : new VirtualMachineError[] {
                    new OutOfMemoryError("synthetic initialization"),
                    new StackOverflowError("synthetic initialization") }) {
                assertThatThrownBy(() -> initializeWithFailure(name, failure)).rootCause().isSameAs(failure);
            }
        }
    }

    /** Unsupported native platforms retain their existing fallback behavior. */
    @Test
    public void anUnavailableNativeDriverStillFallsBack() throws Exception {
        assertThat(initializeWithFailure("io.github.toolfactory.narcissus.Narcissus",
                new UnsatisfiedLinkError("synthetic native failure"))).isEqualTo("StandardReflectionDriver");
    }
}
