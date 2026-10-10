package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ScanResult#getClassDependencyMap()} and {@link ScanResult#getReverseClassDependencyMap()}, which
 * are two views of the same set of dependency edges: one from each class to what it uses, and one from each class
 * to what uses it.
 */
public class ClassDependencyMapTest {
    /** A class that nothing in this test depends on. */
    public static class Leaf {
    }

    /** A class that depends on {@link Leaf}. */
    public static class UsesLeaf {
        /** A method that refers to {@link Leaf}, making it a dependency of this class. */
        public void use() {
            new Leaf().toString();
        }
    }

    /** A second class that depends on {@link Leaf}. */
    public static class AlsoUsesLeaf {
        /** A method that refers to {@link Leaf}, making it a dependency of this class. */
        public void use() {
            new Leaf().toString();
        }
    }

    /** A class named only by a field of {@link DepSuper}. */
    public static class MemberType {
    }

    /** A class named only by the return type of a method of {@link DepSuper}. */
    public static class ReturnType {
    }

    /** A class named only in an annotation parameter value on {@link DepSuper}. */
    public static class AnnotationValueType {
    }

    /** A class named only in a meta-annotation parameter value on {@link DepAnnotation}. */
    public static class MetaAnnotationValueType {
    }

    /** A meta-annotation, on {@link DepAnnotation}. */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface DepMetaAnnotation {
        /**
         * A class.
         *
         * @return the class.
         */
        Class<?> value();
    }

    /** An annotation that carries a meta-annotation. */
    @Retention(RetentionPolicy.RUNTIME)
    @DepMetaAnnotation(MetaAnnotationValueType.class)
    public @interface DepAnnotation {
    }

    /** An {@link Inherited} annotation, on {@link DepSuper}. */
    @Retention(RetentionPolicy.RUNTIME)
    @Inherited
    public @interface DepInheritedAnnotation {
        /**
         * A class.
         *
         * @return the class.
         */
        Class<?> value();
    }

    /** A superclass whose members and annotations name classes that its subclass never names. */
    @DepInheritedAnnotation(AnnotationValueType.class)
    public static class DepSuper {
        /** A field of a type that only this class names. */
        public MemberType memberType;

        /**
         * A method whose return type only this class names.
         *
         * @return null.
         */
        public ReturnType returnType() {
            return null;
        }
    }

    /** A subclass that names nothing but its superclass. */
    public static class DepSub extends DepSuper {
    }

    /** A class whose only annotation carries a meta-annotation. */
    @DepAnnotation
    public static class AnnotatedWithMeta {
    }

    /** A class that names {@link ClassDependencyMapTestCtorParam} only as a constructor parameter type. */
    public static class CtorOnly {
        /**
         * A constructor whose parameter type is named only in the constructor's descriptor.
         *
         * @param ctorParam
         *            ignored.
         */
        public CtorOnly(final ClassDependencyMapTestCtorParam ctorParam) {
        }
    }

    /** The scan result the maps are read from. */
    private static ScanResult scanResult;

    /** Scan the test classes with inter-class dependencies enabled. */
    @BeforeAll
    static void scan() {
        scanResult = new ClassGraph().enableClassInfo().enableClasspath()
                .acceptClasses(Leaf.class.getName(), UsesLeaf.class.getName(), AlsoUsesLeaf.class.getName())
                .enableInterClassDependencies().scan();
    }

    /** Close the scan result. */
    @AfterAll
    static void closeScanResult() {
        scanResult.close();
    }

    /**
     * The {@link ClassInfo} for a test class.
     *
     * @param cls
     *            the class.
     * @return the {@link ClassInfo}.
     */
    private static ClassInfo classInfo(final Class<?> cls) {
        final var ci = scanResult.getClassInfo(cls.getName());
        assertThat(ci).isNotNull();
        return ci;
    }

    /** The forward map holds an entry for every accepted class, listing what that class depends on. */
    @Test
    public void theForwardMapListsWhatEachClassDependsOn() {
        final var dependencyMap = scanResult.getClassDependencyMap();
        assertThat(dependencyMap).containsOnlyKeys(classInfo(Leaf.class), classInfo(UsesLeaf.class),
                classInfo(AlsoUsesLeaf.class));
        assertThat(dependencyMap.get(classInfo(UsesLeaf.class))).contains(classInfo(Leaf.class));
        // Nothing that was scanned is used by Leaf itself
        assertThat(dependencyMap.get(classInfo(Leaf.class))).doesNotContain(classInfo(UsesLeaf.class),
                classInfo(AlsoUsesLeaf.class));
    }

    /** The reverse map lists, for each class that is depended upon, the classes that depend on it. */
    @Test
    public void theReverseMapListsWhatDependsOnEachClass() {
        final var reverseMap = scanResult.getReverseClassDependencyMap();
        assertThat(reverseMap.get(classInfo(Leaf.class))).containsExactly(classInfo(AlsoUsesLeaf.class),
                classInfo(UsesLeaf.class));
        // A class that nothing depends on has no entry at all, rather than an entry with an empty list
        assertThat(reverseMap).doesNotContainKey(classInfo(UsesLeaf.class));
    }

    /** The reverse map holds exactly the edges of the forward map, turned around. */
    @Test
    public void theTwoMapsHoldTheSameEdges() {
        final var forwardEdges = scanResult.getClassDependencyMap().entrySet().stream()
                .flatMap(ent -> ent.getValue().stream().map(dep -> List.of(ent.getKey(), dep))).toList();
        final var reverseEdges = scanResult.getReverseClassDependencyMap().entrySet().stream()
                .flatMap(ent -> ent.getValue().stream().map(dependent -> List.of(dependent, ent.getKey())))
                .toList();
        assertThat(reverseEdges).containsExactlyInAnyOrderElementsOf(forwardEdges);
    }

    /** Both maps are sorted by class name, so that iterating over either of them is repeatable. */
    @Test
    public void bothMapsAreSortedByClassName() {
        assertThat(scanResult.getClassDependencyMap().keySet()).containsExactly(classInfo(AlsoUsesLeaf.class),
                classInfo(Leaf.class), classInfo(UsesLeaf.class));
        assertThat(List.copyOf(scanResult.getReverseClassDependencyMap().keySet()))
                .isSortedAccordingTo(Comparator.naturalOrder());
    }

    /** Both maps are read-only views of the scan result, so they cannot be changed. */
    @Test
    public void bothMapsAreUnmodifiable() {
        assertThatThrownBy(() -> scanResult.getClassDependencyMap().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> scanResult.getReverseClassDependencyMap().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /**
     * Scan the fixtures of the tests of what a class's own dependencies are, with field, method and annotation info
     * and inter-class dependencies enabled, and get the dependencies of a class. Class visibility is ignored,
     * because {@link ClassDependencyMapTestCtorParam} is not public.
     *
     * @param cls
     *            the class.
     * @return the names of the classes that the class depends on.
     */
    private static List<String> ownDependencies(final Class<?> cls) {
        try (var allInfoScan = new ClassGraph().enableClassInfo().enableFieldInfo().enableMethodInfo()
                .enableAnnotationInfo().ignoreClassVisibility().enableClasspath()
                .acceptClasses(MemberType.class.getName(), ReturnType.class.getName(),
                        AnnotationValueType.class.getName(), MetaAnnotationValueType.class.getName(),
                        DepMetaAnnotation.class.getName(), DepAnnotation.class.getName(),
                        DepInheritedAnnotation.class.getName(), DepSuper.class.getName(), DepSub.class.getName(),
                        AnnotatedWithMeta.class.getName(), CtorOnly.class.getName(),
                        ClassDependencyMapTestCtorParam.class.getName())
                .enableInterClassDependencies().scan()) {
            final var ci = allInfoScan.getClassInfo(cls.getName());
            assertThat(ci).isNotNull();
            return ci.getClassDependencies().getNames();
        }
    }

    /**
     * A class does not depend on the classes that the members and annotations of its superclass name, even though
     * it inherits those members and that {@link Inherited} annotation.
     */
    @Test
    public void aClassDoesNotDependOnWhatItsSuperclassNames() {
        assertThat(ownDependencies(DepSub.class)).containsExactly(DepSuper.class.getName());
        assertThat(ownDependencies(DepSuper.class)).containsExactlyInAnyOrder(MemberType.class.getName(),
                ReturnType.class.getName(), AnnotationValueType.class.getName(),
                DepInheritedAnnotation.class.getName());
    }

    /** A class depends on its annotations, but not on their meta-annotations. */
    @Test
    public void aClassDoesNotDependOnTheMetaAnnotationsOfItsAnnotations() {
        assertThat(ownDependencies(AnnotatedWithMeta.class)).containsExactly(DepAnnotation.class.getName());
    }

    /** A class depends on the parameter types of its constructors, which appear only in a descriptor. */
    @Test
    public void aClassDependsOnItsConstructorParameterTypes() {
        assertThat(ownDependencies(CtorOnly.class))
                .containsExactly(ClassDependencyMapTestCtorParam.class.getName());
    }

    /**
     * Without {@link ClassGraph#enableInterClassDependencies()}, both maps throw, even when no class was found and
     * there is no class to ask for its dependencies.
     */
    @Test
    public void bothMapsRequireInterClassDependenciesToBeEnabled() {
        try (var withoutDependencies = new ClassGraph().enableClassInfo().enableClasspath()
                .acceptClasses(Leaf.class.getName() + "DoesNotExist").scan()) {
            assertThat(withoutDependencies.getAllClasses()).isEmpty();
            assertThatThrownBy(withoutDependencies::getClassDependencyMap).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("enableInterClassDependencies");
            assertThatThrownBy(withoutDependencies::getReverseClassDependencyMap)
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("enableInterClassDependencies");
        }
    }
}

/**
 * The constructor parameter type of {@link ClassDependencyMapTest.CtorOnly}. It is a top-level class, because a
 * nested class would be named by a constant pool class entry in the {@code InnerClasses} attribute of
 * {@code CtorOnly}, not only in the constructor's descriptor.
 */
class ClassDependencyMapTestCtorParam {
}
