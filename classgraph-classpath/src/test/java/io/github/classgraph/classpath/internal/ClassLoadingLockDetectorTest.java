package io.github.classgraph.classpath.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import org.junit.jupiter.api.Test;

/** Tests for {@link ClassLoadingLockDetector}. */
public class ClassLoadingLockDetectorTest {
    /** A class that asks the detector what it reports while the class's own static initializer is running. */
    private static class InitializedByAStaticInitializer {
        /** What the detector reported during this class's static initializer. */
        static final String FRAME;

        static {
            FRAME = ClassLoadingLockDetector.findFrameHoldingClassLoadingLock();
        }
    }

    /** A classloader that asks the detector what it reports while the classloader is loading a class. */
    private static class ProbingClassLoader extends ClassLoader {
        /** What the detector reported during {@link #findClass(String)}. */
        String frame;

        @Override
        protected Class<?> findClass(final String name) throws ClassNotFoundException {
            frame = ClassLoadingLockDetector.findFrameHoldingClassLoadingLock();
            throw new ClassNotFoundException(name);
        }
    }

    /** An ordinary method holds no class loading lock, so a scan started from one can use worker threads. */
    @Test
    public void anOrdinaryMethodHoldsNoClassLoadingLock() {
        assertThat(ClassLoadingLockDetector.findFrameHoldingClassLoadingLock()).isNull();
    }

    /** A static initializer holds the initialization lock of the class it is initializing. */
    @Test
    public void aStaticInitializerHoldsAClassLoadingLock() {
        assertThat(InitializedByAStaticInitializer.FRAME)
                .startsWith(InitializedByAStaticInitializer.class.getName() + ".<clinit>");
    }

    /** A classloader that is loading a class holds that class's loading lock. */
    @Test
    public void aClassLoaderThatIsLoadingAClassHoldsAClassLoadingLock() {
        final var classLoader = new ProbingClassLoader();
        assertThat(catchThrowable(() -> classLoader.loadClass("io.github.classgraph.classpath.NoSuchClass")))
                .isInstanceOf(ClassNotFoundException.class);
        assertThat(classLoader.frame).startsWith(ProbingClassLoader.class.getName() + ".findClass");
    }
}
