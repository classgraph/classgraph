package io.github.classgraph.issues.issue463;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.classgraph.ClassGraph;

/**
 * Test that queries return only accepted classes, whether they look "upwards" or "downwards" through the class
 * hierarchy, and whether or not the class the query starts from is itself an external class. The external classes
 * are reported only if {@link ClassGraph#enableExternalClasses()} was called.
 */
public class Issue463Test {
    /** An annotation. */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Ann {
    }

    /** An annotation that is inherited by subclasses. */
    @Retention(RetentionPolicy.RUNTIME)
    @Inherited
    public @interface InheritedAnn {
    }

    /** A meta-annotation. */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface MetaAnn {
    }

    /** A field annotation that is meta-annotated by {@link MetaAnn}. */
    @Retention(RetentionPolicy.RUNTIME)
    @MetaAnn
    public @interface FieldAnn {
    }

    /** An interface. */
    public interface Iface {
    }

    /** The root of the class hierarchy. */
    @Ann
    @InheritedAnn
    public static class Base implements Iface {
    }

    /** The middle of the class hierarchy. */
    public static class Mid extends Base {
    }

    /** The leaf of the class hierarchy, and the only accepted class. */
    public static class Leaf extends Mid {
        /** A field annotated by an external annotation. */
        @FieldAnn
        public int field;
    }

    /** A field, method and method parameter annotation that is meta-annotated by {@link MetaAnn}. */
    @Retention(RetentionPolicy.RUNTIME)
    @MetaAnn
    public @interface MemberAnn {
    }

    /** A superclass with an annotated public field, method and method parameter, which subclasses inherit. */
    public static class MemberBase {
        /** An annotated public field. */
        @MemberAnn
        public int annotatedField;

        /** An annotated public method. */
        @MemberAnn
        public void annotatedMethod() {
        }

        /**
         * A public method with an annotated parameter.
         *
         * @param x
         *            an annotated parameter.
         */
        public void methodWithAnnotatedParam(@MemberAnn final int x) {
        }
    }

    /** A subclass that inherits the annotated members of {@link MemberBase}. */
    public static class MemberLeaf extends MemberBase {
    }

    /** A superclass with an annotated private field, method and method parameter, which are not inherited. */
    @SuppressWarnings("unused")
    public static class PrivateMemberBase {
        /** An annotated private field. */
        @MemberAnn
        private int annotatedField;

        /** An annotated private method. */
        @MemberAnn
        private void annotatedMethod() {
        }

        /**
         * A private method with an annotated parameter.
         *
         * @param x
         *            an annotated parameter.
         */
        private void methodWithAnnotatedParam(@MemberAnn final int x) {
        }
    }

    /** A subclass of {@link PrivateMemberBase}, which inherits none of its annotated members. */
    public static class PrivateMemberLeaf extends PrivateMemberBase {
    }

    /**
     * Only {@code Leaf} is accepted, so {@code Mid}, {@code Base}, {@code Iface} and {@code Ann} are all external
     * classes, reached by extending scanning upwards.
     */
    @Test
    public void downwardQueriesReturnOnlyAcceptedClasses() {
        try (var scanResult = new ClassGraph().enableClasspath().acceptClasses(Leaf.class.getName())
                .enableClassInfo().enableFieldInfo().enableMethodInfo().enableAnnotationInfo()
                .enableStaticFinalFieldConstantInitializerValues().ignoreClassVisibility().ignoreFieldVisibility()
                .ignoreMethodVisibility().scan()) {
            // Upwards: the external classes that Leaf's own classfile chain declares are left out too, since
            // enableExternalClasses() was not called
            assertThat(scanResult.getAllSuperclasses(Leaf.class).getNames()).isEmpty();
            assertThat(scanResult.getAllSuperinterfaces(Leaf.class).getNames()).isEmpty();

            // Downwards: only what was accepted, even though the class each query starts from is itself external
            assertThat(scanResult.getAllSubclasses(Base.class).getNames()).containsOnly(Leaf.class.getName());
            assertThat(scanResult.getAllClassesImplementing(Iface.class).getNames())
                    .containsOnly(Leaf.class.getName());
            assertThat(scanResult.getClassesWithAnnotation(Ann.class).getNames()).isEmpty();
        }
    }

    /**
     * With external classes enabled, the same queries return the external classes too.
     */
    @Test
    public void queriesReturnExternalClassesIfEnabled() {
        try (var scanResult = new ClassGraph().enableClasspath().acceptClasses(Leaf.class.getName())
                .enableClassInfo().enableFieldInfo().enableMethodInfo().enableAnnotationInfo()
                .enableStaticFinalFieldConstantInitializerValues().ignoreClassVisibility().ignoreFieldVisibility()
                .ignoreMethodVisibility().enableExternalClasses().scan()) {
            assertThat(scanResult.getAllSuperclasses(Leaf.class).getNames()).contains(Mid.class.getName(),
                    Base.class.getName());
            assertThat(scanResult.getAllSuperinterfaces(Leaf.class).getNames()).containsOnly(Iface.class.getName());
            assertThat(scanResult.getAllSubclasses(Base.class).getNames()).containsOnly(Mid.class.getName(),
                    Leaf.class.getName());
            assertThat(scanResult.getAllClassesImplementing(Iface.class).getNames())
                    .containsOnly(Base.class.getName(), Mid.class.getName(), Leaf.class.getName());
            assertThat(scanResult.getClassesWithAnnotation(Ann.class).getNames())
                    .containsOnly(Base.class.getName());
        }
    }

    /**
     * An accepted class must still be found when the only path to it runs through an external class: {@code Leaf}
     * inherits {@code @InheritedAnn} from the external class {@code Base}, and its field is annotated by the
     * external annotation {@code FieldAnn}, which is meta-annotated by {@code MetaAnn}.
     */
    @Test
    public void acceptedClassesReachableOnlyThroughExternalClassesAreFound() {
        try (var scanResult = new ClassGraph().enableClasspath().acceptClasses(Leaf.class.getName())
                .enableClassInfo().enableFieldInfo().enableMethodInfo().enableAnnotationInfo()
                .enableStaticFinalFieldConstantInitializerValues().ignoreClassVisibility().ignoreFieldVisibility()
                .ignoreMethodVisibility().scan()) {
            assertThat(scanResult.getClassesWithAnnotation(InheritedAnn.class).getNames())
                    .containsOnly(Leaf.class.getName());
            assertThat(scanResult.getClassesWithFieldAnnotation(MetaAnn.class).getNames())
                    .containsOnly(Leaf.class.getName());
        }
    }

    /**
     * An accepted class that inherits an annotated non-private field, method or method parameter from an external
     * superclass is found by the field, method and method parameter annotation queries, both for the annotation and
     * for its meta-annotation, while the external superclass is left out. A subclass of a class whose annotated
     * members are private is not found, since private members are not inherited. The expected result is the result
     * with external classes enabled, less the external classes.
     *
     * @param acceptAnnotation
     *            whether the annotation and meta-annotation are accepted, rather than external.
     */
    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    public void acceptedSubclassesInheritAnnotatedMembersFromAnExternalSuperclass(final boolean acceptAnnotation) {
        final var acceptedClasses = new ArrayList<>(
                List.of(MemberLeaf.class.getName(), PrivateMemberLeaf.class.getName()));
        if (acceptAnnotation) {
            acceptedClasses.add(MemberAnn.class.getName());
            acceptedClasses.add(MetaAnn.class.getName());
        }
        for (final var enableExternalClasses : new boolean[] { false, true }) {
            var classGraph = new ClassGraph().enableClasspath()
                    .acceptClasses(acceptedClasses.toArray(new String[0])).enableClassInfo().enableFieldInfo()
                    .enableMethodInfo().enableAnnotationInfo().ignoreFieldVisibility().ignoreMethodVisibility();
            if (enableExternalClasses) {
                classGraph = classGraph.enableExternalClasses();
            }
            final var expected = enableExternalClasses
                    ? new String[] { MemberBase.class.getName(), MemberLeaf.class.getName(),
                            PrivateMemberBase.class.getName() }
                    : new String[] { MemberLeaf.class.getName() };
            try (var scanResult = classGraph.scan()) {
                for (final var annotation : List.of(MemberAnn.class, MetaAnn.class)) {
                    assertThat(scanResult.getClassesWithFieldAnnotation(annotation).getNames())
                            .as("getClassesWithFieldAnnotation(%s), external classes enabled: %s",
                                    annotation.getSimpleName(), enableExternalClasses)
                            .containsExactlyInAnyOrder(expected);
                    assertThat(scanResult.getClassesWithMethodAnnotation(annotation).getNames())
                            .as("getClassesWithMethodAnnotation(%s), external classes enabled: %s",
                                    annotation.getSimpleName(), enableExternalClasses)
                            .containsExactlyInAnyOrder(expected);
                    assertThat(scanResult.getClassesWithMethodParameterAnnotation(annotation).getNames())
                            .as("getClassesWithMethodParameterAnnotation(%s), external classes enabled: %s",
                                    annotation.getSimpleName(), enableExternalClasses)
                            .containsExactlyInAnyOrder(expected);
                }
            }
        }
    }

    /**
     * The enclosing classes of an accepted class, and the annotations on its fields, are external classes too if
     * they were not accepted, so they are reported only if {@link ClassGraph#enableExternalClasses()} was called.
     */
    @Test
    public void enclosingClassesAndFieldAnnotationsHonorExternalClasses() {
        try (var scanResult = new ClassGraph().enableClasspath().acceptClasses(Leaf.class.getName())
                .enableClassInfo().enableFieldInfo().enableMethodInfo().enableAnnotationInfo()
                .enableStaticFinalFieldConstantInitializerValues().ignoreClassVisibility().ignoreFieldVisibility()
                .ignoreMethodVisibility().scan()) {
            final var leaf = scanResult.getClassInfo(Leaf.class.getName());
            assertThat(leaf.getEnclosingClasses().getNames()).isEmpty();
            assertThat(leaf.getFieldAnnotations().getNames()).isEmpty();
        }
        try (var scanResult = new ClassGraph().enableClasspath().acceptClasses(Leaf.class.getName())
                .enableClassInfo().enableFieldInfo().enableMethodInfo().enableAnnotationInfo()
                .enableStaticFinalFieldConstantInitializerValues().ignoreClassVisibility().ignoreFieldVisibility()
                .ignoreMethodVisibility().enableExternalClasses().scan()) {
            final var leaf = scanResult.getClassInfo(Leaf.class.getName());
            assertThat(leaf.getEnclosingClasses().getNames()).containsOnly(Issue463Test.class.getName());
            assertThat(leaf.getFieldAnnotations().getNames()).contains(FieldAnn.class.getName());
        }
    }
}
