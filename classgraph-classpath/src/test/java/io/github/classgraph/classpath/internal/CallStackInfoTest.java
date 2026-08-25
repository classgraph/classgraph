package io.github.classgraph.classpath.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import org.junit.jupiter.api.Test;

/** Tests for {@link CallStackInfo}. */
public class CallStackInfoTest {
    /** A class that asks the call stack what it reports while the class's own static initializer is running. */
    private static class InitializedByAStaticInitializer {
        /** The frame holding a class loading lock during this class's static initializer. */
        static final String FRAME;

        static {
            FRAME = CallStackInfo.read().getFrameHoldingClassLoadingLock();
        }
    }

    /** A classloader that asks the call stack what it reports while the classloader is loading a class. */
    private static class ProbingClassLoader extends ClassLoader {
        /** The frame holding a class loading lock during {@link #findClass(String)}. */
        String frame;

        @Override
        protected Class<?> findClass(final String name) throws ClassNotFoundException {
            frame = CallStackInfo.read().getFrameHoldingClassLoadingLock();
            throw new ClassNotFoundException(name);
        }
    }

    /** The call stack names the classloader of the caller, so that the caller's own classpath is searched. */
    @Test
    public void theCallStackNamesTheClassLoaderOfTheCaller() {
        assertThat(CallStackInfo.read().getClassLoaders()).contains(CallStackInfoTest.class.getClassLoader());
    }

    /** The call stack names the module layers of the caller, and whether any caller is on the classpath. */
    @Test
    public void theCallStackNamesTheModuleLayersOfTheCaller() {
        // The tests are run from the classpath, so this class is in an unnamed module, which has no layer
        assertThat(CallStackInfoTest.class.getModule().getLayer()).isNull();
        final var callStackInfo = CallStackInfo.read();
        assertThat(callStackInfo.anyClassIsInAnUnnamedModule()).isTrue();
        // The JDK frames below this one are in the boot layer
        assertThat(callStackInfo.getModuleLayers()).contains(ModuleLayer.boot());
    }

    /** An ordinary method holds no class loading lock, so a scan started from one can use worker threads. */
    @Test
    public void anOrdinaryMethodHoldsNoClassLoadingLock() {
        assertThat(CallStackInfo.read().getFrameHoldingClassLoadingLock()).isNull();
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
