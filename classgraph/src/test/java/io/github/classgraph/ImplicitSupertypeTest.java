package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * A class declaration is rendered as it would be written in Java source, so the supertypes that the compiler adds
 * and that cannot be written in an {@code extends} or {@code implements} clause -- {@code java.lang.Record} for a
 * record, {@code java.lang.Enum} for an enum, and {@code java.lang.annotation.Annotation} for an annotation -- are
 * omitted, in the same way that {@code java.lang.Object} already was.
 */
public class ImplicitSupertypeTest {
    /** An interface for the test classes to implement, so an explicit supertype is still rendered. */
    public interface Marker {
    }

    /** A non-generic record, which has no class type signature. */
    public record Rec(String name) implements Marker {
    }

    /** A generic record, which does have a class type signature. */
    public record GenericRec<T>(T val, List<T> vals) {
    }

    /** A record with a static field, which is not one of its components. */
    public record RecWithStaticField(int x) {
        /** A static field of the record. */
        public static final int Y = 1;
    }

    /** A record whose components are not declared in the order of their names. */
    public record RecWithComponentsOutOfNameOrder(int zb, String za) {
    }

    /** A record with no components. */
    public record RecWithNoComponents() {
    }

    /** An enum, whose implicit superclass is {@code java.lang.Enum}. */
    public enum En implements Marker {
        /** A constant. */
        A
    }

    /** An annotation, whose implicit superinterface is {@code java.lang.annotation.Annotation}. */
    public @interface Ann {
        /** @return the value */
        String value();
    }

    private static ScanResult scan() {
        return new ClassGraph().enableClasspath()
                .acceptPackagesNonRecursive(ImplicitSupertypeTest.class.getPackage().getName()).enableClassInfo()
                .enableFieldInfo().enableMethodInfo().enableAnnotationInfo()
                .enableStaticFinalFieldConstantInitializerValues().ignoreClassVisibility().ignoreFieldVisibility()
                .ignoreMethodVisibility().scan();
    }

    /** A record does not render {@code extends java.lang.Record}, but does render its explicit superinterfaces. */
    @Test
    public void recordDoesNotExtendRecord() {
        try (var scanResult = scan()) {
            assertThat(scanResult.getClassInfo(Rec.class.getName()).toStringWithSimpleNames())
                    .isEqualTo("public static final record Rec(String name) implements Marker");
        }
    }

    /** A generic record renders the {@code record} keyword and its components, not {@code class}. */
    @Test
    public void genericRecordRendersItsComponents() {
        try (var scanResult = scan()) {
            assertThat(scanResult.getClassInfo(GenericRec.class.getName()).toStringWithSimpleNames())
                    .isEqualTo("public static final record GenericRec<T>(T val, List<T> vals)");
        }
    }

    /** A static field of a record is not one of its components, so is not rendered in the component list. */
    @Test
    public void staticFieldOfRecordIsNotAComponent() {
        try (var scanResult = scan()) {
            assertThat(scanResult.getClassInfo(RecWithStaticField.class.getName()).toStringWithSimpleNames())
                    .isEqualTo("public static final record RecWithStaticField(int x)");
        }
    }

    /** The components of a record are rendered in declaration order, not in the order of their names. */
    @Test
    public void recordComponentsAreRenderedInDeclarationOrder() {
        try (var scanResult = scan()) {
            final var classInfo = scanResult.getClassInfo(RecWithComponentsOutOfNameOrder.class.getName());
            assertThat(classInfo.toStringWithSimpleNames())
                    .isEqualTo("public static final record RecWithComponentsOutOfNameOrder(int zb, String za)");
            assertThat(classInfo.toString()).endsWith("(int zb, java.lang.String za)");
        }
    }

    /** A record with no components renders an empty component list, as it is written in Java source. */
    @Test
    public void recordWithNoComponentsRendersAnEmptyComponentList() {
        try (var scanResult = scan()) {
            assertThat(scanResult.getClassInfo(RecWithNoComponents.class.getName()).toStringWithSimpleNames())
                    .isEqualTo("public static final record RecWithNoComponents()");
        }
    }

    /**
     * The names and order of the components of a record are read from its {@code Record} attribute, and their types
     * from its field info, so if field info was not enabled, a record with components is rendered without its
     * component list, and rendering it does not throw. (A record with no components is still rendered with
     * {@code "()"}.)
     */
    @Test
    public void recordWithoutFieldInfoRendersNoComponents() {
        try (var scanResult = new ClassGraph().enableClassInfo().enableClasspath()
                .acceptPackagesNonRecursive(ImplicitSupertypeTest.class.getPackage().getName()).scan()) {
            assertThat(scanResult.getClassInfo(Rec.class.getName()).toStringWithSimpleNames())
                    .isEqualTo("public static final record Rec implements Marker");
        }
    }

    /** An enum does not render {@code extends java.lang.Enum<En>}, but does render its explicit superinterfaces. */
    @Test
    public void enumDoesNotExtendEnum() {
        try (var scanResult = scan()) {
            assertThat(scanResult.getClassInfo(En.class.getName()).toStringWithSimpleNames())
                    .isEqualTo("public static final enum En implements Marker");
        }
    }

    /** An annotation does not render {@code implements java.lang.annotation.Annotation}. */
    @Test
    public void annotationDoesNotImplementAnnotation() {
        try (var scanResult = scan()) {
            assertThat(scanResult.getClassInfo(Ann.class.getName()).toStringWithSimpleNames())
                    .isEqualTo("public abstract static @interface Ann");
        }
    }

    /**
     * {@code java.lang.Record}, {@code java.lang.Enum} and {@code Annotation} are only implicit for their own kind
     * of class, so a class that genuinely extends or implements one of them still renders it.
     */
    @Test
    public void implicitSupertypesAreStillRenderedForOtherClasses() {
        try (var scanResult = new ClassGraph().acceptClasses("java.lang.Enum").enableSystemJars()
                .enableSystemModules().enableClassInfo().enableFieldInfo().enableMethodInfo().enableAnnotationInfo()
                .enableStaticFinalFieldConstantInitializerValues().ignoreClassVisibility().ignoreFieldVisibility()
                .ignoreMethodVisibility().scan()) {
            final var enumClassInfo = scanResult.getClassInfo("java.lang.Enum");
            assertThat(enumClassInfo).isNotNull();
            // java.lang.Enum is a class, not an enum, so its own supertypes are rendered
            assertThat(enumClassInfo.toStringWithSimpleNames())
                    .startsWith("public abstract class Enum<E extends Enum<E>> implements ")
                    .contains("Comparable<E>", "Serializable");
        }
    }
}
