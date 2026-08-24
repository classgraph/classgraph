package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests that the array-typed parameter values of an annotation come back as an array of the element type the
 * annotation declares, both when the annotation class was scanned (so its declared parameter types are known) and
 * when it was not (so the element type has to be worked out from the values themselves).
 */
public class ExternalAnnotationArrayValuesTest {
    /** An enum used as an annotation parameter value. */
    public enum Color {
        /** The first color. */
        RED,
        /** The second color. */
        GREEN
    }

    /** An annotation used as an annotation parameter value. */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Inner {
        /**
         * The number.
         *
         * @return the number.
         */
        int num();
    }

    /** An annotation with one array-typed parameter of each kind. */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface ArrayParams {
        /**
         * @return the ints.
         */
        int[] ints();

        /**
         * @return the longs.
         */
        long[] longs();

        /**
         * @return the shorts.
         */
        short[] shorts();

        /**
         * @return the chars.
         */
        char[] chars();

        /**
         * @return the bytes.
         */
        byte[] bytes();

        /**
         * @return the booleans.
         */
        boolean[] booleans();

        /**
         * @return the floats.
         */
        float[] floats();

        /**
         * @return the doubles.
         */
        double[] doubles();

        /**
         * @return the strings.
         */
        String[] strings();

        /**
         * @return the classes.
         */
        Class<?>[] classes();

        /**
         * @return the colors.
         */
        Color[] colors();

        /**
         * @return the nested annotations.
         */
        Inner[] inners();

        /**
         * @return an array with no elements.
         */
        int[] empty();
    }

    /** The class carrying the annotation. */
    @ArrayParams(ints = { 1, 2 }, longs = { 3L }, shorts = { 4 }, chars = { 'c' }, bytes = { 5 }, //
            booleans = { true, false }, floats = { 6.5f }, doubles = { 7.5 }, strings = { "a", "b" }, //
            classes = { String.class }, colors = { Color.RED, Color.GREEN }, inners = { @Inner(num = 8) }, //
            empty = {})
    public static class Annotated {
    }

    /** A scan that reached the annotation class, so knows the declared type of each parameter. */
    private static ScanResult withAnnotationClass;

    /** A scan that could not reach the annotation class, so has to work the element types out from the values. */
    private static ScanResult withoutAnnotationClass;

    /**
     * Run both scans. The second scan is given a classpath holding only the annotated class, since an annotation
     * class that is on the classpath is read even if it was not accepted, in order to resolve meta-annotations and
     * default parameter values.
     *
     * @param classpathDir
     *            a directory to build the second scan's classpath in.
     * @throws IOException
     *             if the annotated class' classfile could not be copied.
     */
    @BeforeAll
    static void scan(@TempDir final Path classpathDir) throws IOException {
        withAnnotationClass = new ClassGraph().enableClasspath().acceptClasses(Annotated.class.getName(),
                ArrayParams.class.getName(), Inner.class.getName(), Color.class.getName()).enableAnnotationInfo()
                .scan();

        final var classfilePath = Annotated.class.getName().replace('.', '/') + ".class";
        final var target = classpathDir.resolve(classfilePath);
        Files.createDirectories(target.getParent());
        try (var classfile = ExternalAnnotationArrayValuesTest.class.getClassLoader()
                .getResourceAsStream(classfilePath)) {
            assertThat(classfile).isNotNull();
            Files.copy(classfile, target);
        }
        withoutAnnotationClass = new ClassGraph().enableClasspathEntries(classpathDir.toString())
                .enableAnnotationInfo().scan();
        assertThat(withoutAnnotationClass.getAllClasses().getNames()).containsExactly(Annotated.class.getName());
    }

    /** Close both scan results. */
    @AfterAll
    static void closeScanResults() {
        withAnnotationClass.close();
        withoutAnnotationClass.close();
    }

    /**
     * The value of one of the annotation's parameters.
     *
     * @param scanResult
     *            the scan result to read the value from.
     * @param parameterName
     *            the name of the annotation parameter.
     * @return the parameter value.
     */
    private static Object value(final ScanResult scanResult, final String parameterName) {
        final var annotationInfo = scanResult.getClassInfo(Annotated.class.getName()).getAllAnnotationInfo()
                .get(ArrayParams.class.getName());
        assertThat(annotationInfo).isNotNull();
        final var parameterValue = annotationInfo.getParameterValues().getValue(parameterName);
        assertThat(parameterValue).isNotNull();
        return parameterValue;
    }

    /** An array of a primitive type is returned as an array of that primitive type, not of the boxed type. */
    @Test
    public void primitiveArraysAreReturnedAsPrimitiveArrays() {
        for (final ScanResult scanResult : new ScanResult[] { withAnnotationClass, withoutAnnotationClass }) {
            assertThat(value(scanResult, "ints")).isEqualTo(new int[] { 1, 2 });
            assertThat(value(scanResult, "longs")).isEqualTo(new long[] { 3L });
            assertThat(value(scanResult, "shorts")).isEqualTo(new short[] { 4 });
            assertThat(value(scanResult, "chars")).isEqualTo(new char[] { 'c' });
            assertThat(value(scanResult, "bytes")).isEqualTo(new byte[] { 5 });
            assertThat(value(scanResult, "booleans")).isEqualTo(new boolean[] { true, false });
            assertThat(value(scanResult, "floats")).isEqualTo(new float[] { 6.5f });
            assertThat(value(scanResult, "doubles")).isEqualTo(new double[] { 7.5 });
        }
    }

    /** An array of strings is returned as a {@code String[]}, not as an {@code Object[]}. */
    @Test
    public void stringArraysAreReturnedAsStringArrays() {
        for (final ScanResult scanResult : new ScanResult[] { withAnnotationClass, withoutAnnotationClass }) {
            assertThat(value(scanResult, "strings")).isEqualTo(new String[] { "a", "b" });
        }
    }

    /**
     * Arrays of the reference types that an annotation parameter can hold -- class references, enum constants and
     * nested annotations -- are returned as arrays of the ClassGraph objects that stand in for those values.
     */
    @Test
    public void referenceArraysAreReturnedAsArraysOfTheStandInObjects() {
        for (final ScanResult scanResult : new ScanResult[] { withAnnotationClass, withoutAnnotationClass }) {
            assertThat((Object[]) value(scanResult, "classes")).singleElement()
                    .isInstanceOf(AnnotationClassRef.class)
                    .satisfies(ref -> assertThat(((AnnotationClassRef) ref).getName())
                            .isEqualTo(String.class.getName()));
            assertThat((Object[]) value(scanResult, "colors")).hasSize(2)
                    .allSatisfy(enumValue -> assertThat(enumValue).isInstanceOf(AnnotationEnumValue.class));
            assertThat((Object[]) value(scanResult, "inners")).singleElement().isInstanceOf(AnnotationInfo.class)
                    .satisfies(inner -> assertThat(((AnnotationInfo) inner).getParameterValues().getValue("num"))
                            .isEqualTo(8));
        }
    }

    /**
     * An empty array can only be given its declared element type if the annotation class was scanned: with nothing
     * in the array, there is no value to read the element type from.
     */
    @Test
    public void anEmptyArrayIsTypedOnlyIfTheAnnotationClassWasScanned() {
        assertThat(value(withAnnotationClass, "empty")).isEqualTo(new int[0]);
        assertThat(value(withoutAnnotationClass, "empty")).isEqualTo(new Object[0]);
    }
}
