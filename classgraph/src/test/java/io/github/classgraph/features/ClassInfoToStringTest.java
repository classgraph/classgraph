package io.github.classgraph.features;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

/**
 * {@link io.github.classgraph.ClassInfo#toString()} renders the extends and implements clauses of a class the same
 * way whether or not the class is generic: a supertype carries the type annotations written on that clause, and not
 * the declaration annotations of the supertype's own declaration.
 */
class ClassInfoToStringTest {
    /** A declaration annotation that can only be placed on a type declaration. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @interface Decl {
    }

    /** A type-use annotation. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE_USE)
    @interface Use {
    }

    /** A superclass with declaration annotations. */
    @Deprecated
    @Decl
    static class Base {
    }

    /** An interface with a declaration annotation. */
    @Decl
    interface Iface {
    }

    /** A non-generic class whose supertypes have declaration annotations. */
    static class Sub extends Base implements Iface {
    }

    /** As {@link Sub}, but generic. */
    // The type parameter is the fixture, and is deliberately not used by the body
    @SuppressWarnings("unused")
    static class GenericSub<T> extends Base implements Iface {
    }

    /** A non-generic class with type annotations in its extends and implements clauses. */
    static class UseSub extends @Use Base implements @Use Iface {
    }

    /** An interface that extends an interface with a declaration annotation. */
    interface SubIface extends Iface {
    }

    /** A non-generic class with a declaration annotation of its own. */
    @Decl
    static class AnnotatedSub extends Base {
    }

    private static ScanResult scan() {
        return new ClassGraph().enableClasspath()
                .acceptPackagesNonRecursive(ClassInfoToStringTest.class.getPackage().getName()).enableClassInfo()
                .enableAnnotationInfo().ignoreClassVisibility().scan();
    }

    /** A supertype's declaration annotations are not rendered in the extends or implements clause. */
    @Test
    void supertypeDeclarationAnnotationsAreNotRendered() {
        try (var scanResult = scan()) {
            assertThat(scanResult.getClassInfo(Sub.class.getName()).toStringWithSimpleNames())
                    .isEqualTo("static class Sub extends Base implements Iface");
            assertThat(scanResult.getClassInfo(SubIface.class.getName()).toStringWithSimpleNames())
                    .isEqualTo("abstract static interface SubIface extends Iface");
            // The generic and non-generic forms agree
            assertThat(scanResult.getClassInfo(GenericSub.class.getName()).toStringWithSimpleNames())
                    .isEqualTo("static class GenericSub<T> extends Base implements Iface");
        }
    }

    /** The type annotations in the extends and implements clauses of a non-generic class are rendered. */
    @Test
    void typeAnnotationsOnSupertypesAreRendered() {
        try (var scanResult = scan()) {
            final var classInfo = scanResult.getClassInfo(UseSub.class.getName());
            assertThat(classInfo.toStringWithSimpleNames())
                    .isEqualTo("static class UseSub extends @Use Base implements @Use Iface");
            assertThat(classInfo.toString()).isEqualTo(classInfo.getTypeSignatureOrTypeDescriptor().toString());
        }
    }

    /** A class's own declaration annotations are still rendered before its declaration. */
    @Test
    void ownDeclarationAnnotationsAreRendered() {
        try (var scanResult = scan()) {
            assertThat(scanResult.getClassInfo(AnnotatedSub.class.getName()).toStringWithSimpleNames())
                    .isEqualTo("@Decl static class AnnotatedSub extends Base");
            assertThat(scanResult.getClassInfo(PackagePrivateAnnotated.class.getName()).toStringWithSimpleNames())
                    .isEqualTo("@Decl class PackagePrivateAnnotated implements Iface");
        }
    }
}

/** A top-level package-private class with a declaration annotation, which has no access modifier to render. */
@ClassInfoToStringTest.Decl
class PackagePrivateAnnotated implements ClassInfoToStringTest.Iface {
}
