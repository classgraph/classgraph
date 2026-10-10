package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

/**
 * Tests that the parameter values of an annotation can be read by two threads at once. An array-typed value is held
 * as an Object[] array of boxed values until it is first read, and is then replaced by an array of its element
 * type, so the first reads of a value change it.
 *
 * <p>
 * In the first two tests, the two threads read the same values in the same order, so they keep meeting on the value
 * that one of them is converting. In the others, one thread hashes or compares an annotation while the other
 * converts its values.
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

    /**
     * The length of the array-typed value that one thread converts while another thread hashes or compares it.
     * The array is long, so that the conversion takes long enough for the other thread to meet it.
     */
    private static final int LONG_ARRAY_LENGTH = 20_000;

    /** The number of times the race between a conversion and a hash or a comparison is run. */
    private static final int NUM_TRIALS = 50;

    /**
     * Scan the annotation and the annotated class.
     *
     * @return the scan result.
     */
    private static ScanResult scan() {
        return new ClassGraph().enableClassInfo()
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
        final Object[] boxed = new Object[VALUES.length];
        for (int i = 0; i < VALUES.length; i++) {
            boxed[i] = VALUES[i];
        }
        return new AnnotationParameterValue(name, boxed);
    }

    /**
     * Check that a parameter value has been converted to an int[] array holding {@link #VALUES}.
     *
     * @param value
     *            the parameter value, as returned by {@link AnnotationParameterValue#getValue()}.
     */
    private static void checkConverted(final Object value) {
        if (!(value instanceof int[]) || !Arrays.equals((int[]) value, VALUES)) {
            throw new AssertionError("Expected int[] " + Arrays.toString(VALUES) + ", got "
                    + (value instanceof int[] ? "int[] " + Arrays.toString((int[]) value)
                            : value instanceof Object[] ? "Object[] " + Arrays.toString((Object[]) value)
                                    : String.valueOf(value)));
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
     * @throws InterruptedException
     *             if interrupted while joining the threads.
     */
    private static List<Throwable> runConcurrently(final Runnable task0, final Runnable task1)
            throws InterruptedException {
        final Queue<Throwable> failures = new ConcurrentLinkedQueue<>();
        final CyclicBarrier barrier = new CyclicBarrier(2);
        final List<Thread> threads = new ArrayList<>();
        for (final Runnable task : Arrays.asList(task0, task1)) {
            final Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        barrier.await();
                        task.run();
                    } catch (final Throwable t) {
                        failures.add(t);
                    }
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
     * Two threads can read the array-typed values given at annotation use sites at the same time, and every value
     * is converted afterwards.
     *
     * @throws InterruptedException
     *             if interrupted while joining the threads.
     */
    @Test
    public void declaredValuesCanBeReadByTwoThreadsAtOnce() throws InterruptedException {
        try (ScanResult scanResult = scan()) {
            // Each use site has its own values, so give the two threads many use sites to meet on
            final AnnotationInfo[] annotationInfos = new AnnotationInfo[NUM_VALUES];
            for (int i = 0; i < NUM_VALUES; i++) {
                annotationInfos[i] = new AnnotationInfo(WithDefault.class.getName(),
                        new AnnotationParameterValueList(Collections.singletonList(unconvertedValue("values"))));
                annotationInfos[i].setScanResult(scanResult);
            }
            final Runnable readAll = new Runnable() {
                @Override
                public void run() {
                    for (final AnnotationInfo annotationInfo : annotationInfos) {
                        checkConverted(annotationInfo.getParameterValues().getValue("values"));
                    }
                }
            };
            final List<Throwable> failures = runConcurrently(readAll, readAll);
            assertThat(failures).isEmpty();
            for (final AnnotationInfo annotationInfo : annotationInfos) {
                checkConverted(annotationInfo.getParameterValues().getValue("values"));
            }
        }
    }

    /**
     * Two threads can read the default values of an annotation class at the same time, one through
     * {@link AnnotationInfo#getParameterValues()} and the other through
     * {@link AnnotationInfo#getDefaultParameterValues()}, and every default value is converted afterwards.
     *
     * @throws InterruptedException
     *             if interrupted while joining the threads.
     */
    @Test
    public void defaultValuesCanBeReadByTwoThreadsAtOnce() throws InterruptedException {
        try (ScanResult scanResult = scan()) {
            final ClassInfo annotationClassInfo = scanResult.getClassInfo(WithDefault.class.getName());
            final AnnotationInfo annotationInfo = scanResult.getClassInfo(Annotated.class.getName())
                    .getAnnotationInfo(WithDefault.class.getName());
            assertThat(annotationInfo).isNotNull();

            // An annotation class has one list of default values, shared by all of its use sites, so give it many
            // unconverted default values for the two threads to meet on. The parameters are not declared by the
            // annotation class, so their element type is worked out from their values.
            final List<AnnotationParameterValue> defaultValues = new ArrayList<>();
            for (int i = 0; i < NUM_VALUES; i++) {
                defaultValues.add(unconvertedValue(String.format("p%06d", i)));
            }
            annotationClassInfo.annotationDefaultParamValues = new AnnotationParameterValueList(defaultValues);
            annotationClassInfo.annotationDefaultParamValuesHasBeenConvertedToPrimitive = false;

            final List<Throwable> failures = runConcurrently(new Runnable() {
                @Override
                public void run() {
                    annotationInfo.getParameterValues();
                }
            }, new Runnable() {
                @Override
                public void run() {
                    annotationInfo.getDefaultParameterValues();
                }
            });
            assertThat(failures).isEmpty();
            final AnnotationParameterValueList convertedDefaultValues = annotationInfo.getDefaultParameterValues();
            assertThat(convertedDefaultValues).hasSize(NUM_VALUES);
            for (final AnnotationParameterValue defaultValue : convertedDefaultValues) {
                checkConverted(defaultValue.getValue());
            }
        }
    }

    /**
     * Create an annotation with one array-typed parameter value that has not yet been converted, holding
     * {@link #LONG_ARRAY_LENGTH} ones as an Object[] array of boxed values.
     *
     * @param scanResult
     *            the scan result.
     * @return the annotation.
     */
    private static AnnotationInfo unconvertedLongArrayAnnotation(final ScanResult scanResult) {
        final Object[] boxed = new Object[LONG_ARRAY_LENGTH];
        Arrays.fill(boxed, 1);
        final AnnotationInfo annotationInfo = new AnnotationInfo(WithDefault.class.getName(),
                new AnnotationParameterValueList(
                        Collections.singletonList(new AnnotationParameterValue("values", boxed))));
        annotationInfo.setScanResult(scanResult);
        return annotationInfo;
    }

    /**
     * Run a check on an annotation over and over in one thread, while another thread converts the annotation's
     * array-typed value by reading its parameter values for the first time. Repeat with a new annotation
     * {@link #NUM_TRIALS} times.
     *
     * @param scanResult
     *            the scan result.
     * @param check
     *            the check, which throws {@link AssertionError} if it fails.
     * @throws InterruptedException
     *             if interrupted while joining the threads.
     */
    private static void checkWhileConverting(final ScanResult scanResult, final Consumer<AnnotationInfo> check)
            throws InterruptedException {
        // Time one check, and start the conversion a little later in each trial, spread over two checks, so that
        // the conversion meets the check at a different point in each trial
        final long checkStartTime = System.nanoTime();
        check.accept(unconvertedLongArrayAnnotation(scanResult));
        final long checkNanos = System.nanoTime() - checkStartTime;
        for (int trial = 0; trial < NUM_TRIALS; trial++) {
            final AnnotationInfo annotationInfo = unconvertedLongArrayAnnotation(scanResult);
            final AtomicBoolean converted = new AtomicBoolean();
            final long delayNanos = 2 * checkNanos * trial / NUM_TRIALS;
            final List<Throwable> failures = runConcurrently(new Runnable() {
                @Override
                public void run() {
                    final long startTime = System.nanoTime();
                    while (System.nanoTime() - startTime < delayNanos) {
                        // Wait
                    }
                    annotationInfo.getParameterValues();
                    converted.set(true);
                }
            }, new Runnable() {
                @Override
                public void run() {
                    do {
                        check.accept(annotationInfo);
                    } while (!converted.get());
                }
            });
            assertThat(failures).as("trial " + trial).isEmpty();
        }
    }

    /**
     * Check that {@link AnnotationInfo#hashCode()} gives the same hash code while another thread converts the
     * annotation's values. A different hash code would leave an annotation that is in a hash set in the wrong
     * bucket.
     *
     * @param scanResult
     *            the scan result.
     * @throws InterruptedException
     *             if interrupted while joining the threads.
     */
    private static void checkHashCodeWhileConverting(final ScanResult scanResult) throws InterruptedException {
        // The hash code that hashCode() gave before any thread converted the values, worked out by hand
        final int[] ones = new int[LONG_ARRAY_LENGTH];
        Arrays.fill(ones, 1);
        final int expectedHashCode = WithDefault.class.getName().hashCode() * 7 + "values".hashCode() * 3
                + Arrays.deepHashCode(new Object[] { ones });
        assertThat(unconvertedLongArrayAnnotation(scanResult).hashCode()).isEqualTo(expectedHashCode);
        checkWhileConverting(scanResult, new Consumer<AnnotationInfo>() {
            @Override
            public void accept(final AnnotationInfo annotationInfo) {
                final int hashCode = annotationInfo.hashCode();
                if (hashCode != expectedHashCode) {
                    throw new AssertionError("Expected hash code " + expectedHashCode + ", got " + hashCode);
                }
            }
        });
    }

    /**
     * Check that {@link AnnotationInfo#equals(Object)} and {@link AnnotationInfo#compareTo(AnnotationInfo)} find
     * two equal annotations equal while another thread converts the values of one of them.
     *
     * @param scanResult
     *            the scan result.
     * @throws InterruptedException
     *             if interrupted while joining the threads.
     */
    private static void checkEqualsWhileConverting(final ScanResult scanResult) throws InterruptedException {
        final AnnotationInfo convertedAnnotationInfo = unconvertedLongArrayAnnotation(scanResult);
        convertedAnnotationInfo.getParameterValues();
        checkWhileConverting(scanResult, new Consumer<AnnotationInfo>() {
            @Override
            public void accept(final AnnotationInfo annotationInfo) {
                if (!annotationInfo.equals(convertedAnnotationInfo)
                        || convertedAnnotationInfo.compareTo(annotationInfo) != 0) {
                    throw new AssertionError("Expected the annotations to be equal");
                }
            }
        });
    }

    /**
     * Run one of the checks of a hash code or a comparison during a conversion, in this JVM. Called by
     * {@link #runInInterpretedJvm(String)}.
     *
     * @param args
     *            the name of the check: "hashCode" or "equals".
     * @throws InterruptedException
     *             if interrupted while joining the threads.
     */
    public static void main(final String[] args) throws InterruptedException {
        try (ScanResult scanResult = scan()) {
            if ("hashCode".equals(args[0])) {
                checkHashCodeWhileConverting(scanResult);
            } else {
                checkEqualsWhileConverting(scanResult);
            }
        }
    }

    /**
     * Run one of the checks of a hash code or a comparison during a conversion in a new JVM, with the JIT compiler
     * turned off. Once compiled, the methods that are raced run so fast that the race is rarely met.
     *
     * @param check
     *            the name of the check: "hashCode" or "equals".
     * @throws IOException
     *             if the JVM could not be started.
     * @throws InterruptedException
     *             if interrupted while waiting for the JVM.
     */
    private static void runInInterpretedJvm(final String check) throws IOException, InterruptedException {
        final String java = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        final Process process = new ProcessBuilder(java, "-Xint", "-cp", System.getProperty("java.class.path"),
                AnnotationConversionRaceTest.class.getName(), check).redirectErrorStream(true).start();
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (InputStream inputStream = process.getInputStream()) {
            final byte[] buf = new byte[8192];
            for (int n; (n = inputStream.read(buf)) > 0;) {
                output.write(buf, 0, n);
            }
        }
        assertThat(process.waitFor()).as(output.toString("UTF-8")).isZero();
    }

    /**
     * {@link AnnotationInfo#hashCode()} gives the same hash code while another thread converts the annotation's
     * values.
     *
     * @throws IOException
     *             if the JVM could not be started.
     * @throws InterruptedException
     *             if interrupted while waiting for the JVM.
     */
    @Test
    public void hashCodeDoesNotChangeWhileAnotherThreadConvertsTheValues() throws IOException, InterruptedException {
        runInInterpretedJvm("hashCode");
    }

    /**
     * {@link AnnotationInfo#equals(Object)} and {@link AnnotationInfo#compareTo(AnnotationInfo)} find two equal
     * annotations equal while another thread converts the values of one of them.
     *
     * @throws IOException
     *             if the JVM could not be started.
     * @throws InterruptedException
     *             if interrupted while waiting for the JVM.
     */
    @Test
    public void equalsDoesNotChangeWhileAnotherThreadConvertsTheValues() throws IOException, InterruptedException {
        runInInterpretedJvm("equals");
    }

    /**
     * {@link AnnotationInfo#hashCode()}, {@link AnnotationInfo#equals(Object)} and
     * {@link AnnotationInfo#compareTo(AnnotationInfo)} convert the values first, and still work after the
     * {@link ScanResult} is closed, including for a nested annotation value.
     */
    @Test
    public void hashCodeAndEqualsWorkAfterClose() {
        final AnnotationInfo[] annotationInfos = new AnnotationInfo[2];
        try (ScanResult scanResult = scan()) {
            for (int i = 0; i < annotationInfos.length; i++) {
                final AnnotationInfo nested = new AnnotationInfo(WithDefault.class.getName(),
                        new AnnotationParameterValueList(Collections.singletonList(unconvertedValue("values"))));
                annotationInfos[i] = new AnnotationInfo(WithDefault.class.getName(),
                        new AnnotationParameterValueList(Arrays.asList(new AnnotationParameterValue("nested", nested),
                                unconvertedValue("values"))));
                annotationInfos[i].setScanResult(scanResult);
            }
        }
        assertThat(annotationInfos[0].hashCode()).isEqualTo(annotationInfos[1].hashCode());
        assertThat(annotationInfos[0]).isEqualTo(annotationInfos[1]);
        assertThat(annotationInfos[0].compareTo(annotationInfos[1])).isZero();
    }
}
