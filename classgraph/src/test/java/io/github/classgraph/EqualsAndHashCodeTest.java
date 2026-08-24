package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    /** An enum whose constants are used as annotation parameter values. */
    public enum Fruit {
        /** The first constant. */
        APPLE,
        /** The second constant. */
        BANANA
    }

    /** An annotation with an enum constant as its parameter value. */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Favourite {
        /**
         * The favourite fruit.
         *
         * @return the favourite fruit.
         */
        Fruit value();
    }

    /** A class whose fields carry enum-valued annotations. */
    public static class Favourites {
        /** A field annotated with one enum constant. */
        @Favourite(Fruit.BANANA)
        public int first;

        /** A field annotated with the same enum constant as {@link #first}. */
        @Favourite(Fruit.BANANA)
        public int second;

        /** A field annotated with a different constant of the same enum. */
        @Favourite(Fruit.APPLE)
        public int third;
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

        /** An array field whose element type has a type parameter. */
        public List<String>[] stringLists;

        /** An array field whose element type has a different type parameter. */
        public List<Integer>[] integerLists;

        /** An array field with the same element type as {@link #stringLists} but one more dimension. */
        public List<String>[][] stringListsTwoDimensional;
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
        return new ClassGraph().enableClasspath().acceptPackages(EqualsAndHashCodeTest.class.getPackageName())
                .enableAllInfo().scan();
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

        // A plain copy of the reachable list holds the same entries, but treats every one of them as direct
        assertThat(new AnnotationInfoList(reachable).directOnly().getNames())
                .containsExactlyElementsOf(reachable.getNames());
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

    /** A list of all subclasses reports only the direct ones from {@code directOnly()}. */
    @Test
    public void aClassListKnowsWhichOfItsEntriesAreDirectlyRelated() {
        final var subclasses = classInfo(scanResult, Annotated.class).getAllSubclasses();
        assertThat(subclasses.getNames()).containsExactly(Sub.class.getName(), SubSub.class.getName());

        // Only Sub extends Annotated directly
        final var direct = subclasses.directOnly();
        assertThat(direct.getNames()).containsExactly(Sub.class.getName());

        // A plain copy holds the same classes, but treats every one of them as directly related
        assertThat(new ClassInfoList(List.copyOf(subclasses)).directOnly().getNames())
                .containsExactly(Sub.class.getName(), SubSub.class.getName());
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

    /**
     * A result list obeys the {@link List#equals(Object)} contract, so it compares equal to a plain list holding
     * the same elements whichever of the two is the receiver, and the two are interchangeable in a hash set. Which
     * of the entries are directly related is reported by {@code directOnly()}, and is not part of the comparison.
     */
    @Test
    public void resultListsObeyTheListEqualsContract() {
        final var subclasses = classInfo(scanResult, Annotated.class).getAllSubclasses();
        final List<ClassInfo> plainClasses = new ArrayList<>(subclasses);
        assertThat(subclasses).isEqualTo(plainClasses).hasSameHashCodeAs(plainClasses);
        assertThat(plainClasses).isEqualTo(subclasses);
        // Looked up through the set's own contains(), so that this needs equals() and hashCode() to agree, rather
        // than through an assertion that walks the set and compares each element in turn
        assertThat(hashSetOf(plainClasses).contains(subclasses)).as("plain list set contains ClassInfoList")
                .isTrue();
        assertThat(hashSetOf(subclasses).contains(plainClasses)).as("ClassInfoList set contains plain list")
                .isTrue();

        final var annotations = classInfo(scanResult, Annotated.class).getAllAnnotationInfo();
        final List<AnnotationInfo> plainAnnotations = new ArrayList<>(annotations);
        assertThat(annotations).isEqualTo(plainAnnotations).hasSameHashCodeAs(plainAnnotations);
        assertThat(plainAnnotations).isEqualTo(annotations);
        assertThat(hashSetOf(plainAnnotations).contains(annotations))
                .as("plain list set contains AnnotationInfoList").isTrue();
        assertThat(hashSetOf(annotations).contains(plainAnnotations))
                .as("AnnotationInfoList set contains plain list").isTrue();
    }

    /**
     * A {@link HashSet} holding a single list, so that a lookup in it goes through the list's {@code hashCode()}
     * and {@code equals()}.
     *
     * @param <T>
     *            the element type of the list.
     * @param list
     *            the list to put in the set.
     * @return the set.
     */
    private static <T> Set<List<T>> hashSetOf(final List<T> list) {
        final Set<List<T>> set = new HashSet<>();
        set.add(list);
        return set;
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

    /**
     * Two array types are equal ignoring type parameters if their element types are, which is what lets an array of
     * a raw type be matched against an array of the parameterized type.
     */
    @Test
    public void arrayTypesCanBeComparedIgnoringTheirTypeParameters() {
        final var stringLists = fieldType("stringLists");
        final var integerLists = fieldType("integerLists");
        // The two differ only in the type parameter of their element type
        assertThat(stringLists).isNotEqualTo(integerLists);
        assertThat(stringLists.equalsIgnoringTypeParams(stringLists)).isTrue();
        assertThat(stringLists.equalsIgnoringTypeParams(integerLists)).isTrue();

        // A different number of dimensions, a different element type, and a non-array type are all still different
        assertThat(stringLists.equalsIgnoringTypeParams(fieldType("stringListsTwoDimensional"))).isFalse();
        assertThat(stringLists.equalsIgnoringTypeParams(fieldType("first"))).isFalse();
        assertThat(stringLists.equalsIgnoringTypeParams(fieldType("notAnArray"))).isFalse();
        assertThat(stringLists.equalsIgnoringTypeParams(null)).isFalse();
    }

    /**
     * The enum constant of an annotation parameter value.
     *
     * @param scanRes
     *            the scan result to look the class up in.
     * @param fieldName
     *            the name of the field of {@link Favourites} to read the annotation from.
     * @return the enum constant the annotation names.
     */
    private static AnnotationEnumValue favourite(final ScanResult scanRes, final String fieldName) {
        final var fieldInfo = classInfo(scanRes, Favourites.class).getFieldInfo(fieldName);
        assertThat(fieldInfo).isNotNull();
        final var annotationInfo = fieldInfo.getAllAnnotationInfo().get(Favourite.class.getName());
        assertThat(annotationInfo).isNotNull();
        return (AnnotationEnumValue) annotationInfo.getParameterValues().getValue("value");
    }

    /**
     * An enum constant used as an annotation parameter value is identified by its enum class and its constant name,
     * and is reported without the enum class being loaded.
     */
    @Test
    public void enumConstantsAreEqualIfTheyAreTheSameConstantOfTheSameEnum() {
        final var banana = favourite(scanResult, "first");
        assertThat(banana.getClassName()).isEqualTo(Fruit.class.getName());
        assertThat(banana.getValueName()).isEqualTo(Fruit.BANANA.name());
        assertThat(banana.getName()).isEqualTo(Fruit.class.getName() + "." + Fruit.BANANA.name());
        assertThat(banana.toString()).isEqualTo(banana.getName());

        final var bananaAgain = favourite(secondScanResult, "second");
        assertThat(banana).isEqualTo(banana).isEqualTo(bananaAgain).hasSameHashCodeAs(bananaAgain);
        assertThat(banana).isEqualByComparingTo(bananaAgain);

        // A different constant of the same enum is a different value, and sorts by constant name
        final var apple = favourite(scanResult, "third");
        assertThat(banana).isNotEqualTo(apple).isNotEqualTo(banana.getName());
        assertThat(apple).isLessThan(banana);
    }

    /** An annotation on the element type of an array is part of what makes two array types equal. */
    @Test
    public void anAnnotatedArrayTypeIsNotEqualToTheUnannotatedOne() {
        final var annotated = fieldType("annotated");
        assertThat(annotated.toString()).contains(Checked.class.getSimpleName());
        assertThat(annotated).isNotEqualTo(fieldType("third"));
    }
}
