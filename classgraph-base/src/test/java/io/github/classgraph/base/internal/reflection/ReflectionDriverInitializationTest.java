package io.github.classgraph.base.internal.reflection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;

import org.junit.jupiter.api.Test;

/** Driver initialization distinguishes optional-library failures from fatal VM errors. */
public class ReflectionDriverInitializationTest {
    /**
     * Initialize the reflection utility in isolation, failing the optional driver lookup.
     *
     * @param failure
     *            the synthetic error to throw, without actually exhausting memory or the stack.
     * @return the initialized class if fallback succeeds.
     * @throws IOException
     *             if the classloader cannot be closed.
     * @throws ClassNotFoundException
     *             if the utility cannot be loaded.
     */
    private static Class<?> initializeWithFailure(final Error failure) throws IOException, ClassNotFoundException {
        final var location = ReflectionUtils.class.getProtectionDomain().getCodeSource().getLocation();
        try (var loader = new URLClassLoader(new URL[] { location }, null) {
            @Override
            protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
                if (name.equals("io.github.toolfactory.narcissus.Narcissus")) {
                    throw failure;
                }
                return super.loadClass(name, resolve);
            }
        }) {
            return Class.forName(ReflectionUtils.class.getName(), true, loader);
        }
    }

    /** Fatal errors are propagated by identity, not converted to an ordinary fallback. */
    @Test
    public void fatalInitializationErrorsAreRethrown() {
        for (final var failure : new VirtualMachineError[] { new OutOfMemoryError("synthetic initialization"),
                new StackOverflowError("synthetic initialization") }) {
            assertThatThrownBy(() -> initializeWithFailure(failure)).isSameAs(failure);
        }
    }

    /** A missing native implementation still uses standard reflection. */
    @Test
    public void anUnavailableNativeDriverStillFallsBack() throws IOException, ClassNotFoundException {
        assertThat(initializeWithFailure(new UnsatisfiedLinkError("synthetic native failure")).getName())
                .isEqualTo(ReflectionUtils.class.getName());
    }
}
