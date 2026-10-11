package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;

import org.junit.jupiter.api.Test;

/**
 * Tests that the parameter values of an annotation can be read by two threads at once. An array-typed value is held
 * as an Object[] array of boxed values until it is first read, and is then replaced by an array of its element
 * type, so the first reads of a value change it.
 *
 * <p>
 * The two threads read the same values in the same order, so they keep meeting on the value that one of them is
 * converting. Each test runs the two threads in a child JVM that never compiles the method that converts a value. A
 * compiled method may load a field once for several reads of it, which hides a race between those reads, so in a
 * JVM that compiles the method the race shows only until the method is compiled, and the test would fail only some
 * of the time.
 */
public class AnnotationConversionRaceTest {
    /** An annotation with an array-typed parameter that has a default value. */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface WithDefault {
        /**
         * @return the values.
         */
        int[] values() default { 1, 2, 3 };
    }

    /** A class annotated with {@link WithDefault}, using its default value. */
    @WithDefault
    public static class Annotated {
    }

    /** The number of array-typed values that the two threads convert at once. */
    private static final int NUM_VALUES = 300_000;

    /** The values held, as boxed values, by every array-typed value the tests create. */
    private static final int[] VALUES = { 1, 2, 3 };

    /** The argument that makes the child JVM read the values given at annotation use sites. */
    private static final String DECLARED = "declared";

    /** The argument that makes the child JVM read the default values of an annotation class. */
    private static final String DEFAULTS = "defaults";

    /** The start of the line on which the child JVM prints the number of reads that threw. */
    private static final String NUM_FAILURES = "NUM_FAILURES=";

    /**
     * Scan the annotation and the annotated class.
     *
     * @return the scan result.
     */
    private static ScanResult scan() {
        return new ClassGraph().enableClassInfo().enableClasspath()
                .acceptClasses(Annotated.class.getName(), WithDefault.class.getName()).enableAnnotationInfo()
                .scan();
    }

    /**
     * Create an array-typed annotation parameter value that has not yet been converted.
     *
     * @param name
     *            the parameter name.
     * @return the parameter value, holding {@link #VALUES} as an Object[] array of boxed values.
     */
    private static AnnotationParameterValue unconvertedValue(final String name) {
        return new AnnotationParameterValue(name, Arrays.stream(VALUES).boxed().toArray());
    }

    /**
     * Check that a parameter value has been converted to an int[] array holding {@link #VALUES}.
     *
     * @param value
     *            the parameter value, as returned by {@link AnnotationParameterValue#getValue()}.
     */
    private static void checkConverted(final Object value) {
        if (!(value instanceof final int[] ints) || !Arrays.equals(ints, VALUES)) {
            throw new AssertionError("Expected int[] " + Arrays.toString(VALUES) + ", got "
                    + (value instanceof final Object[] objects ? "Object[] " + Arrays.toString(objects) : value));
        }
    }

    /**
     * Run two tasks at once, each in its own thread, and wait for both to finish.
     *
     * @param task0
     *            the first task.
     * @param task1
     *            the second task.
     * @return what the tasks threw.
     * @throws Exception
     *             if the threads could not be started or joined.
     */
    private static List<Throwable> runConcurrently(final Runnable task0, final Runnable task1) throws Exception {
        final Queue<Throwable> failures = new ConcurrentLinkedQueue<>();
        final var barrier = new CyclicBarrier(2);
        final List<Thread> threads = new ArrayList<>();
        for (final Runnable task : List.of(task0, task1)) {
            final var thread = new Thread(() -> {
                try {
                    barrier.await();
                    task.run();
                } catch (final Throwable t) {
                    failures.add(t);
                }
            }, "AnnotationConversionRaceTest-worker");
            threads.add(thread);
            thread.start();
        }
        for (final Thread thread : threads) {
            thread.join();
        }
        return new ArrayList<>(failures);
    }

    /**
     * Read the array-typed values given at many annotation use sites, in two threads at once, then check that every
     * value was converted.
     *
     * @return what the two threads threw.
     * @throws Exception
     *             if the threads could not be started or joined.
     */
    private static List<Throwable> readDeclaredValuesConcurrently() throws Exception {
        try (var scanResult = scan()) {
            // Each use site has its own values, so give the two threads many use sites to meet on
            final var annotationInfos = new AnnotationInfo[NUM_VALUES];
            for (var i = 0; i < NUM_VALUES; i++) {
                annotationInfos[i] = new AnnotationInfo(WithDefault.class.getName(),
                        new AnnotationParameterValueList(List.of(unconvertedValue("values"))));
                annotationInfos[i].setScanResult(scanResult);
            }
            final Runnable readAll = () -> {
                for (final AnnotationInfo annotationInfo : annotationInfos) {
                    checkConverted(annotationInfo.getParameterValues().getValue("values"));
                }
            };
            final var failures = runConcurrently(readAll, readAll);
            for (final AnnotationInfo annotationInfo : annotationInfos) {
                checkConverted(annotationInfo.getParameterValues().getValue("values"));
            }
            return failures;
        }
    }

    /**
     * Read the default values of an annotation class in two threads at once, one through
     * {@link AnnotationInfo#getParameterValues()} and the other through
     * {@link AnnotationInfo#getDefaultParameterValues()}, then check that every default value was converted.
     *
     * @return what the two threads threw.
     * @throws Exception
     *             if the threads could not be started or joined.
     */
    private static List<Throwable> readDefaultValuesConcurrently() throws Exception {
        try (var scanResult = scan()) {
            final var annotationClassInfo = scanResult.getClassInfo(WithDefault.class.getName());
            final var annotationInfo = scanResult.getClassInfo(Annotated.class.getName()).getAllAnnotationInfo()
                    .get(WithDefault.class.getName());
            if (annotationInfo == null || !annotationInfo.getDeclaredParameterValues().isEmpty()) {
                throw new AssertionError("Expected a use of " + WithDefault.class.getName() + " with no values");
            }

            // An annotation class has one list of default values, shared by all of its use sites, so give it many
            // unconverted default values for the two threads to meet on. The parameters are not declared by the
            // annotation class, so their element type is worked out from their values.
            final List<AnnotationParameterValue> defaultValues = new ArrayList<>();
            for (var i = 0; i < NUM_VALUES; i++) {
                defaultValues.add(unconvertedValue(String.format("p%06d", i)));
            }
            annotationClassInfo.annotationDefaultParamValues = new AnnotationParameterValueList(defaultValues);
            annotationClassInfo.annotationDefaultParamValuesHasBeenConvertedToPrimitive = false;

            final var failures = runConcurrently(annotationInfo::getParameterValues,
                    annotationInfo::getDefaultParameterValues);
            final var convertedDefaultValues = annotationInfo.getDefaultParameterValues();
            if (convertedDefaultValues.size() != NUM_VALUES) {
                throw new AssertionError(
                        "Expected " + NUM_VALUES + " default values, got " + convertedDefaultValues.size());
            }
            for (final AnnotationParameterValue defaultValue : convertedDefaultValues) {
                checkConverted(defaultValue.getValue());
            }
            return failures;
        }
    }

    /**
     * Run one of the two races in the child JVM started by {@link #runInChildJvm(String)}, and print the number of
     * reads that threw, after the stack trace of the first one.
     *
     * @param args
     *            {@link #DECLARED} or {@link #DEFAULTS}, the race to run.
     * @throws Exception
     *             if the threads could not be started or joined, or a value was not converted.
     */
    public static void main(final String[] args) throws Exception {
        final var failures = switch (args[0]) {
        case DECLARED -> readDeclaredValuesConcurrently();
        case DEFAULTS -> readDefaultValuesConcurrently();
        default -> throw new IllegalArgumentException("Unknown race: " + args[0]);
        };
        if (!failures.isEmpty()) {
            failures.get(0).printStackTrace(System.out);
        }
        System.out.println(NUM_FAILURES + failures.size());
    }

    /**
     * Run {@link #main(String[])} in a child JVM that does not compile
     * {@code AnnotationParameterValue.convertWrapperArraysToPrimitiveArrays}, and check that no read threw.
     *
     * @param race
     *            {@link #DECLARED} or {@link #DEFAULTS}, the race to run.
     * @throws Exception
     *             if the child JVM could not be run.
     */
    private static void runInChildJvm(final String race) throws Exception {
        final var javaExecutable = ProcessHandle.current().info().command()
                .orElseGet(() -> Path.of(System.getProperty("java.home"), "bin", "java").toString());
        final var output = ChildJvm.run(new ProcessBuilder(javaExecutable, "-XX:CompileCommand=quiet",
                "-XX:CompileCommand=exclude," + AnnotationParameterValue.class.getName()
                        + "::convertWrapperArraysToPrimitiveArrays",
                "-cp", System.getProperty("java.class.path"), AnnotationConversionRaceTest.class.getName(), race));
        assertThat(output).as("Child JVM output:%n%s", output).contains(NUM_FAILURES + 0);
    }

    /** Two threads can read the array-typed values given at annotation use sites at the same time. */
    @Test
    public void declaredValuesCanBeReadByTwoThreadsAtOnce() throws Exception {
        runInChildJvm(DECLARED);
    }

    /**
     * Two threads can read the default values of an annotation class at the same time, one through
     * {@link AnnotationInfo#getParameterValues()} and the other through
     * {@link AnnotationInfo#getDefaultParameterValues()}.
     */
    @Test
    public void defaultValuesCanBeReadByTwoThreadsAtOnce() throws Exception {
        runInChildJvm(DEFAULTS);
    }
}
