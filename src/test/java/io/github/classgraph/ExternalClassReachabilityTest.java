package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import org.junit.jupiter.api.Test;

/**
 * Test that an accepted class is still found when the only path to it runs through an external class.
 */
public class ExternalClassReachabilityTest {
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
    @InheritedAnn
    public static class Base implements Iface {
    }

    /** The middle of the class hierarchy. */
    public static class Mid extends Base {
    }

    /** The leaf of the class hierarchy. */
    public static class Leaf extends Mid {
        /** A field annotated by an external annotation. */
        @FieldAnn
        public int field;
    }

    /**
     * {@code Iface} is accepted, but the only class that declares {@code implements Iface} is the external class
     * {@code Base}, so {@code Leaf} is only reachable through {@code Base}.
     */
    @Test
    public void classesImplementingReachableThroughExternalClass() {
        try (ScanResult scanResult = new ClassGraph()
                .acceptClasses(Iface.class.getName(), Leaf.class.getName()).enableAllInfo().scan()) {
            assertThat(scanResult.getClassesImplementing(Iface.class.getName()).getNames())
                    .contains(Leaf.class.getName());
        }
    }

    /**
     * {@code InheritedAnn} is accepted, but the only class it annotates is the external class {@code Base}, so
     * {@code Leaf}, which inherits it, is only reachable through {@code Base}.
     */
    @Test
    public void classesWithInheritedAnnotationReachableThroughExternalClass() {
        try (ScanResult scanResult = new ClassGraph()
                .acceptClasses(InheritedAnn.class.getName(), Leaf.class.getName()).enableAllInfo().scan()) {
            assertThat(scanResult.getClassesWithAnnotation(InheritedAnn.class.getName()).getNames())
                    .contains(Leaf.class.getName());
        }
    }

    /**
     * {@code MetaAnn} is accepted, but the annotation it meta-annotates, {@code FieldAnn}, is external, so
     * {@code Leaf}, whose field is annotated by {@code FieldAnn}, is only reachable through {@code FieldAnn}.
     */
    @Test
    public void classesWithMetaAnnotatedFieldReachableThroughExternalAnnotation() {
        try (ScanResult scanResult = new ClassGraph()
                .acceptClasses(MetaAnn.class.getName(), Leaf.class.getName()).enableAllInfo().scan()) {
            assertThat(scanResult.getClassesWithFieldAnnotation(MetaAnn.class.getName()).getNames())
                    .contains(Leaf.class.getName());
        }
    }
}
