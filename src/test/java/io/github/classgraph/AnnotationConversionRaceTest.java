package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
 * converting.
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
}
