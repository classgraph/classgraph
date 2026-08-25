package io.github.classgraph.issues.issue463;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import org.junit.jupiter.api.Test;

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

    /**
     * Only {@code Leaf} is accepted, so {@code Mid}, {@code Base}, {@code Iface} and {@code Ann} are all external
     * classes, reached by extending scanning upwards.
     */
    @Test
    public void downwardQueriesReturnOnlyAcceptedClasses() {
        try (var scanResult = new ClassGraph().enableClasspath().acceptClasses(Leaf.class.getName()).enableAllInfo()
                .scan()) {
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
        try (var scanResult = new ClassGraph().enableClasspath().acceptClasses(Leaf.class.getName()).enableAllInfo()
                .enableExternalClasses().scan()) {
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
        try (var scanResult = new ClassGraph().enableClasspath().acceptClasses(Leaf.class.getName()).enableAllInfo()
                .scan()) {
            assertThat(scanResult.getClassesWithAnnotation(InheritedAnn.class).getNames())
                    .containsOnly(Leaf.class.getName());
            assertThat(scanResult.getClassesWithFieldAnnotation(MetaAnn.class).getNames())
                    .containsOnly(Leaf.class.getName());
        }
    }

    /**
     * The outer classes of an accepted class, and the annotations on its fields, are external classes too if they
     * were not accepted, so they are reported only if {@link ClassGraph#enableExternalClasses()} was called.
     */
    @Test
    public void outerClassesAndFieldAnnotationsHonourExternalClasses() {
        try (var scanResult = new ClassGraph().enableClasspath().acceptClasses(Leaf.class.getName()).enableAllInfo()
                .scan()) {
            final var leaf = scanResult.getClassInfo(Leaf.class.getName());
            assertThat(leaf.getOuterClasses().getNames()).isEmpty();
            assertThat(leaf.getFieldAnnotations().getNames()).isEmpty();
        }
        try (var scanResult = new ClassGraph().enableClasspath().acceptClasses(Leaf.class.getName()).enableAllInfo()
                .enableExternalClasses().scan()) {
            final var leaf = scanResult.getClassInfo(Leaf.class.getName());
            assertThat(leaf.getOuterClasses().getNames()).containsOnly(Issue463Test.class.getName());
            assertThat(leaf.getFieldAnnotations().getNames()).contains(FieldAnn.class.getName());
        }
    }
}
