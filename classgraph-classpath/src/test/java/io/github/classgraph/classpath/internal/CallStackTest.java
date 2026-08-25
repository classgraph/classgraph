package io.github.classgraph.classpath.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import org.junit.jupiter.api.Test;

/** Tests for {@link CallStack}. */
public class CallStackTest {
    /** A class that asks the call stack what it reports while the class's own static initializer is running. */
    private static class InitializedByAStaticInitializer {
        /** The frame holding a class loading lock during this class's static initializer. */
        static final String FRAME;

        static {
            FRAME = CallStack.read().getFrameHoldingClassLoadingLock();
        }
    }

    /** A classloader that asks the call stack what it reports while the classloader is loading a class. */
    private static class ProbingClassLoader extends ClassLoader {
        /** The frame holding a class loading lock during {@link #findClass(String)}. */
        String frame;

        @Override
        protected Class<?> findClass(final String name) throws ClassNotFoundException {
            frame = CallStack.read().getFrameHoldingClassLoadingLock();
            throw new ClassNotFoundException(name);
        }
    }

    /** The call stack names the class of every frame, innermost frame first. */
    @Test
    public void theCallStackNamesTheClassOfEveryFrame() {
        // The walk starts inside CallStack#read, so this test class is the frame below it
        assertThat(CallStack.read().getClassContext()).startsWith(CallStack.class, CallStackTest.class);
    }

    /** An ordinary method holds no class loading lock, so a scan started from one can use worker threads. */
    @Test
    public void anOrdinaryMethodHoldsNoClassLoadingLock() {
        assertThat(CallStack.read().getFrameHoldingClassLoadingLock()).isNull();
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
