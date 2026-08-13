package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Tests for the equality contracts of the result objects that define their own {@code equals()}. Two objects that
 * describe the same thing have to be equal even when they came from different scans, since that is what lets scan
 * results be compared, deduplicated, and used as map keys.
 */
public class EqualsAndHashCodeTest {
    /** A meta-annotation. */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Meta {
    }

    /** An annotation that is itself annotated with the meta-annotation. */
    @Meta
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Direct {
    }

    /** An annotation that can be applied to a use of a type. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE_USE)
    public @interface Checked {
    }

    /** A class carrying the annotation that has a meta-annotation. */
    @Direct
    public static class Annotated {
    }

    /** A direct subclass of {@link Annotated}. */
    public static class Sub extends Annotated {
    }

    /** An indirect subclass of {@link Annotated}. */
    public static class SubSub extends Sub {
    }

    /** A class with fields whose types are compared. */
    public static class Fields {
        /** An array field. */
        public int[][] first;

        /** A second field of the same type as {@link #first}. */
        public int[][] second;

        /** An array field of a different type. */
        public long[] third;

        /** A field whose type is not an array type. */
        public int notAnArray;

        /** An array field with an annotation on its element type. */
        public @Checked int[] annotated;
    }

    /** A second class with a field of the same name and type as one in {@link Fields}. */
    public static class OtherFields {
        /** A field with the same name and type as {@link Fields#first}. */
        public int[][] first;
    }

    /** The scan result the objects under test come from. */
    private static ScanResult scanResult;

    /** A second scan of the same classes, to compare objects that did not come from the same scan. */
    private static ScanResult secondScanResult;

    /**
     * Scan the test classes.
     *
     * @return the scan result.
     */
    private static ScanResult scan() {
        return new ClassGraph().acceptPackages(EqualsAndHashCodeTest.class.getPackageName()).enableAllInfo().scan();
    }

    /** Scan the test classes twice. */
    @BeforeAll
    static void scanTestClasses() {
        scanResult = scan();
        secondScanResult = scan();
    }

    /** Close both scan results. */
    @AfterAll
    static void closeScanResults() {
        scanResult.close();
        secondScanResult.close();
    }

    /**
     * The {@link ClassInfo} for a test class.
     *
     * @param scanRes
     *            the scan result to look the class up in.
     * @param cls
     *            the class.
     * @return the {@link ClassInfo}.
     */
    private static ClassInfo classInfo(final ScanResult scanRes, final Class<?> cls) {
        final var ci = scanRes.getClassInfo(cls.getName());
        assertThat(ci).isNotNull();
        return ci;
    }

    /**
     * The type of a field of {@link Fields}.
     *
     * @param fieldName
     *            the field name.
     * @return the field's type signature.
     */
    private static TypeSignature fieldType(final String fieldName) {
        final var fieldInfo = classInfo(scanResult, Fields.class).getFieldInfo(fieldName);
        assertThat(fieldInfo).isNotNull();
        return fieldInfo.getTypeSignatureOrTypeDescriptor();
    }

    /**
     * A list of the annotations that can be reached from a class is not equal to a list of only its direct ones.
     */
    @Test
    public void anAnnotationListKnowsWhichOfItsEntriesAreDirect() {
        final var reachable = classInfo(scanResult, Annotated.class).getAllAnnotationInfo();
        assertThat(reachable.getNames()).contains(Direct.class.getName(), Meta.class.getName());

        final var direct = reachable.directOnly();
        assertThat(direct.getNames()).containsExactly(Direct.class.getName());
        assertThat(reachable).isNotEqualTo(direct);

        // A plain copy of the reachable list holds the same entries, but has lost which of them are direct
        assertThat(reachable).isNotEqualTo(new AnnotationInfoList(reachable));
    }

    /** Annotation lists holding the same entries are equal, whichever scan they came from. */
    @Test
    public void annotationListsWithTheSameEntriesAreEqual() {
        final var reachable = classInfo(scanResult, Annotated.class).getAllAnnotationInfo();
        final var reachableAgain = classInfo(secondScanResult, Annotated.class).getAllAnnotationInfo();
        assertThat(reachable).isEqualTo(reachable).isEqualTo(reachableAgain).hasSameHashCodeAs(reachableAgain);
        assertThat(reachable.directOnly()).isEqualTo(reachableAgain.directOnly())
                .hasSameHashCodeAs(reachableAgain.directOnly());

        assertThat(reachable).isNotEqualTo(classInfo(scanResult, Sub.class).getAllAnnotationInfo())
                .isNotEqualTo(reachable.getNames());
    }

    /** A list of all subclasses is not equal to a plain list of the same classes, which are all direct. */
    @Test
    public void aClassListKnowsWhichOfItsEntriesAreDirectlyRelated() {
        final var subclasses = classInfo(scanResult, Annotated.class).getAllSubclasses();
        assertThat(subclasses.getNames()).containsExactly(Sub.class.getName(), SubSub.class.getName());
        assertThat(subclasses).isNotEqualTo(new ClassInfoList(List.copyOf(subclasses)));

        // Only Sub extends Annotated directly, so the direct-only list is a plain list of that one class
        final var direct = subclasses.directOnly();
        assertThat(direct.getNames()).containsExactly(Sub.class.getName());
        assertThat(direct).isEqualTo(new ClassInfoList(List.copyOf(direct))).isNotEqualTo(subclasses);
    }

    /** Class lists holding the same classes are equal, whichever scan they came from. */
    @Test
    public void classListsWithTheSameEntriesAreEqual() {
        final var subclasses = classInfo(scanResult, Annotated.class).getAllSubclasses();
        final var subclassesAgain = classInfo(secondScanResult, Annotated.class).getAllSubclasses();
        assertThat(subclasses).isEqualTo(subclasses).isEqualTo(subclassesAgain).hasSameHashCodeAs(subclassesAgain);

        assertThat(subclasses).isNotEqualTo(classInfo(scanResult, Sub.class).getAllSubclasses())
                .isNotEqualTo(subclasses.getNames());
    }

    /** A field is identified by the class that declares it and its name. */
    @Test
    public void fieldsAreEqualIfTheyAreTheSameFieldOfTheSameClass() {
        final var first = classInfo(scanResult, Fields.class).getFieldInfo().get("first");
        final var firstAgain = classInfo(secondScanResult, Fields.class).getFieldInfo().get("first");
        assertThat(first).isEqualTo(first).isEqualTo(firstAgain).hasSameHashCodeAs(firstAgain);

        // A different field of the same class, and a field of the same name in a different class, are both different
        assertThat(first).isNotEqualTo(classInfo(scanResult, Fields.class).getFieldInfo().get("second"))
                .isNotEqualTo(classInfo(scanResult, OtherFields.class).getFieldInfo().get("first"))
                .isNotEqualTo("first");
    }

    /** Array types are equal if they have the same element type, the same dimensions, and the same annotations. */
    @Test
    public void arrayTypesAreEqualIfTheyDescribeTheSameArray() {
        final var first = fieldType("first");
        assertThat(first).isInstanceOf(ArrayTypeSignature.class);
        assertThat(first).isEqualTo(first).isEqualTo(fieldType("second")).hasSameHashCodeAs(fieldType("second"));

        assertThat(first).isNotEqualTo(fieldType("third")).isNotEqualTo(fieldType("notAnArray"))
                .isNotEqualTo(first.toString());
    }

    /** An annotation on the element type of an array is part of what makes two array types equal. */
    @Test
    public void anAnnotatedArrayTypeIsNotEqualToTheUnannotatedOne() {
        final var annotated = fieldType("annotated");
        assertThat(annotated.toString()).contains(Checked.class.getSimpleName());
        assertThat(annotated).isNotEqualTo(fieldType("third"));
    }
}
