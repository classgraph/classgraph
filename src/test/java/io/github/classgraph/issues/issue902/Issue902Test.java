package io.github.classgraph.issues.issue902;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.TimerTask;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;

/**
 * Test that the hierarchy above an accepted class is completed from classfiles
 * in system modules, without having to call
 * {@link ClassGraph#enableSystemJarsAndModules()}.
 */
public class Issue902Test {
    /** Implements an interface directly. */
    public abstract static class AbstractRunnableImplementation implements Runnable {
    }

    /** Implements an interface through an accepted superclass. */
    public static class RunnableImplementation extends AbstractRunnableImplementation {
        @Override
        public void run() {
        }
    }

    /** Implements an interface through a superclass in a system module. */
    public static class TimerTaskImplementation extends TimerTask {
        @Override
        public void run() {
        }
    }

    /**
     * A class implements an interface that only the classfile of its system
     * superclass names.
     */
    @Test
    public void interfaceOfSystemSuperclassIsFound() {
        try (var scanResult = new ClassGraph().acceptPackages(Issue902Test.class.getPackage().getName())
                .enableClassInfo().scan()) {
            final var throughAcceptedSuperclass = scanResult.getClassInfo(RunnableImplementation.class.getName());
            assertThat(throughAcceptedSuperclass.implementsInterface(Runnable.class)).isTrue();

            final var throughSystemSuperclass = scanResult.getClassInfo(TimerTaskImplementation.class.getName());
            assertThat(throughSystemSuperclass.implementsInterface(Runnable.class)).isTrue();

            assertThat(scanResult.getAllClassesImplementing(Runnable.class).getNames())
                    .contains(RunnableImplementation.class.getName(), TimerTaskImplementation.class.getName());
        }
    }

    /**
     * The superclass chain of a class in a system module is completed all the way
     * to Object.
     */
    @Test
    public void systemSuperclassChainIsCompleted() {
        try (var scanResult = new ClassGraph().acceptPackages(Issue902Test.class.getPackage().getName())
                .enableClassInfo().scan()) {
            final var timerTask = scanResult.getClassInfo(TimerTask.class.getName());
            assertThat(timerTask).isNotNull();
            assertThat(timerTask.getAllSuperclasses().getNames()).containsExactly("java.lang.Object");
            assertThat(timerTask.getAllInterfaces().getNames()).containsExactly(Runnable.class.getName());
        }
    }

    /**
     * A class read from a system module in order to complete a hierarchy is an
     * external class, so it is not listed among the scanned classes, and its module
     * is not listed among the scanned modules.
     */
    @Test
    public void systemClassesAreNotAddedToTheScanResult() {
        try (var scanResult = new ClassGraph().acceptPackages(Issue902Test.class.getPackage().getName())
                .enableClassInfo().scan()) {
            assertThat(scanResult.getAllClasses().getNames()).doesNotContain(TimerTask.class.getName());
            assertThat(scanResult.getModuleInfo().getNames()).doesNotContain("java.base");
            assertThat(scanResult.getPackageInfo().getNames()).doesNotContain("java.util");
        }
    }
}
