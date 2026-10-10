package io.github.classgraph.classpath.internal;

/**
 * Prints the frame that {@link CallStackInfo} reports as holding a class loading lock while a static initializer is
 * running. Run in a child JVM by {@link CallStackInfoTest}, with ClassGraph on the boot classpath, so that no class
 * in the call stack has a classloader or a module layer. It also prints the classloader of {@link CallStackInfo},
 * so that the test can check that it really was loaded by the bootstrap classloader.
 */
public final class CallStackInfoPrinter {
    /** Cannot be instantiated. */
    private CallStackInfoPrinter() {
    }

    /** A class whose static initializer prints what the call stack reports, as name=value lines. */
    static final class InitializedByAStaticInitializer {
        /** Cannot be instantiated. */
        private InitializedByAStaticInitializer() {
        }

        static {
            System.out.println("classLoader=" + CallStackInfo.class.getClassLoader());
            System.out.println("frame=" + CallStackInfo.read().getFrameHoldingClassLoadingLock());
        }

        /** Run the static initializer, if it has not already run. */
        static void initialize() {
            // The static initializer runs before the first call to a static method
        }
    }

    /**
     * Run the static initializer of {@link InitializedByAStaticInitializer}.
     *
     * @param args
     *            ignored.
     */
    public static void main(final String[] args) {
        InitializedByAStaticInitializer.initialize();
    }
}
