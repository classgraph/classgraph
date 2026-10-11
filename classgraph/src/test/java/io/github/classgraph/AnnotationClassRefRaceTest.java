package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * Tests that two threads can find the {@link ClassInfo} of the same {@link AnnotationClassRef} at once. The type
 * descriptor of a class reference is parsed when it is first needed, so the first calls change the class reference.
 *
 * <p>
 * The two threads read the same class references in the same order, so they keep meeting on the class reference
 * that one of them is parsing. The test runs the two threads in a child JVM that never compiles the method that
 * parses the type descriptor, since compiling it makes the race much rarer, and the test would then fail only some
 * of the time.
 */
public class AnnotationClassRefRaceTest {
    /** The number of class references that the two threads read at once. */
    private static final int NUM_REFS = 300_000;

    /** The start of the line on which the child JVM prints the number of wrong results. */
    private static final String NUM_FAILURES = "NUM_FAILURES=";

    /**
     * Check the {@link ClassInfo} of a class reference: it has to be the scanned class, or for an array type, an
     * {@link ArrayClassInfo} that belongs to the scan result.
     *
     * @param classRef
     *            the class reference.
     * @param scanResult
     *            the scan result.
     * @return true if the {@link ClassInfo} is correct.
     */
    private static boolean hasCorrectClassInfo(final AnnotationClassRef classRef, final ScanResult scanResult) {
        final var classInfo = classRef.getClassInfo();
        return classInfo != null && classInfo.scanResult == scanResult;
    }

    /**
     * Find the {@link ClassInfo} of many new class references, in two threads at once, then once more in this
     * thread, and print the number of wrong results.
     *
     * @param args
     *            unused.
     * @throws Exception
     *             if the threads could not be started or joined.
     */
    public static void main(final String[] args) throws Exception {
        try (var scanResult = new ClassGraph().enableClassInfo().enableClasspath()
                .acceptClasses(AnnotationClassRefRaceTest.class.getName()).scan()) {
            final var descriptor = "L" + AnnotationClassRefRaceTest.class.getName().replace('.', '/') + ";";
            final var classRefs = new AnnotationClassRef[NUM_REFS];
            for (var i = 0; i < NUM_REFS; i++) {
                // Alternate between a class and an array of that class
                classRefs[i] = new AnnotationClassRef(i % 2 == 0 ? descriptor : "[" + descriptor);
                classRefs[i].setScanResult(scanResult);
            }
            final var numFailures = new AtomicInteger();
            final var barrier = new CyclicBarrier(2);
            final List<Thread> threads = new ArrayList<>();
            for (var t = 0; t < 2; t++) {
                final var thread = new Thread(() -> {
                    try {
                        barrier.await();
                        for (final AnnotationClassRef classRef : classRefs) {
                            if (!hasCorrectClassInfo(classRef, scanResult)) {
                                numFailures.incrementAndGet();
                            }
                        }
                    } catch (final Exception e) {
                        e.printStackTrace(System.out);
                        numFailures.incrementAndGet();
                    }
                }, "AnnotationClassRefRaceTest-worker");
                threads.add(thread);
                thread.start();
            }
            for (final Thread thread : threads) {
                thread.join();
            }
            // A wrong result that was cached shows here too
            for (final AnnotationClassRef classRef : classRefs) {
                if (!hasCorrectClassInfo(classRef, scanResult)) {
                    numFailures.incrementAndGet();
                }
            }
            System.out.println(NUM_FAILURES + numFailures.get());
        }
    }

    /** Two threads can find the {@link ClassInfo} of the same class reference at the same time. */
    @Test
    public void classInfoCanBeFoundByTwoThreadsAtOnce() throws Exception {
        final var javaExecutable = ProcessHandle.current().info().command()
                .orElseGet(() -> Path.of(System.getProperty("java.home"), "bin", "java").toString());
        final var output = ChildJvm.run(new ProcessBuilder(javaExecutable, "-XX:CompileCommand=quiet",
                "-XX:CompileCommand=exclude," + AnnotationClassRef.class.getName() + "::getTypeSignature", "-cp",
                System.getProperty("java.class.path"), AnnotationClassRefRaceTest.class.getName()));
        assertThat(output).as("Child JVM output:%n%s", output).contains(NUM_FAILURES + 0);
    }
}
