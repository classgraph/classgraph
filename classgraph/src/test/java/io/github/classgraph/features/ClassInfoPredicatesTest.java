package io.github.classgraph.features;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ScanResult;

/** An annotation on a field. */
@Retention(RetentionPolicy.RUNTIME)
@interface PredicatesFieldAnnotation {
}

/** An annotation on a method. */
@Retention(RetentionPolicy.RUNTIME)
@interface PredicatesMethodAnnotation {
}

/** An annotation on a method parameter. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
@interface PredicatesParameterAnnotation {
}

/** An annotation that is not placed on anything. */
@Retention(RetentionPolicy.RUNTIME)
@interface PredicatesUnusedAnnotation {
}

/**
 * Tests the {@code ClassInfo} predicates that answer a yes/no question about a class: whether it has a field, a
 * method, an annotation on one of those, a superclass, or an interface. Each of them comes in a "declared" form,
 * which asks only about the class itself, and a plain form, which also asks the classes it inherits from.
 */
public class ClassInfoPredicatesTest {
    /** An interface that declares a method, so that a method can be inherited from an interface. */
    interface Interface {
        /** A method declared by the interface. */
        void interfaceMethod();
    }

    /** An interface that nothing under test implements. */
    interface UnimplementedInterface {
    }

    /** A superclass, with an annotation on a field, a method, and a method parameter. */
    static class Base {
        /** A field declared by the superclass. */
        @PredicatesFieldAnnotation
        int baseField;

        /** A method declared by the superclass. */
        @PredicatesMethodAnnotation
        void baseMethod() {
        }

        /**
         * A method with an annotated parameter, declared by the superclass.
         *
         * @param parameter
         *            the annotated parameter.
         */
        void baseMethodWithAnnotatedParameter(final @PredicatesParameterAnnotation int parameter) {
        }
    }

    /** A subclass that declares a field and a method of its own, and inherits the rest. */
    static class Derived extends Base implements Interface {
        /** A field declared by the subclass. */
        int derivedField;

        /** A method declared by the subclass. */
        void derivedMethod() {
        }

        @Override
        public void interfaceMethod() {
        }
    }

    /** The scan result the classes under test come from. */
    private static ScanResult scanResult;

    /** Scan the test classes. */
    @BeforeAll
    static void scanTestClasses() {
        scanResult = new ClassGraph().acceptPackages(ClassInfoPredicatesTest.class.getPackageName()).enableAllInfo()
                .scan();
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

    // -----------------------------------------------------------------------------------------------------------

    /** A class declares only its own fields, but has its own fields and the fields of its superclasses. */
    @Test
    public void aFieldIsDeclaredByOneClassAndHadByItsSubclasses() {
        final var derived = classInfo(Derived.class);
        assertThat(derived.hasDeclaredField("derivedField")).isTrue();
        assertThat(derived.hasDeclaredField("baseField")).isFalse();
        assertThat(derived.hasDeclaredField("noSuchField")).isFalse();

        assertThat(derived.hasField("derivedField")).isTrue();
        assertThat(derived.hasField("baseField")).isTrue();
        assertThat(derived.hasField("noSuchField")).isFalse();

        // The superclass does not have the fields of its subclasses
        assertThat(classInfo(Base.class).hasField("derivedField")).isFalse();
    }

    /**
     * A class declares a field with an annotation if one of its own fields carries it, and has a field with an
     * annotation if one of the fields it inherits carries it too.
     */
    @Test
    public void aFieldAnnotationIsFoundOnTheClassThatDeclaresTheFieldAndOnItsSubclasses() {
        final var derived = classInfo(Derived.class);
        assertThat(derived.hasDeclaredFieldAnnotation(PredicatesFieldAnnotation.class.getName())).isFalse();
        assertThat(derived.hasFieldAnnotation(PredicatesFieldAnnotation.class.getName())).isTrue();
        assertThat(derived.hasFieldAnnotation(PredicatesUnusedAnnotation.class.getName())).isFalse();

        final var base = classInfo(Base.class);
        assertThat(base.hasDeclaredFieldAnnotation(PredicatesFieldAnnotation.class.getName())).isTrue();
        assertThat(base.hasDeclaredFieldAnnotation(PredicatesUnusedAnnotation.class.getName())).isFalse();

        // The annotation can be named by its class rather than by its name
        assertThat(derived.hasFieldAnnotation(PredicatesFieldAnnotation.class)).isTrue();
        assertThat(derived.hasFieldAnnotation(PredicatesUnusedAnnotation.class)).isFalse();
        assertThat(base.hasDeclaredFieldAnnotation(PredicatesFieldAnnotation.class)).isTrue();
        assertThat(base.hasDeclaredFieldAnnotation(PredicatesUnusedAnnotation.class)).isFalse();
    }

    /**
     * A class declares only its own methods, but has its own methods and the methods of its superclasses and
     * interfaces.
     */
    @Test
    public void aMethodIsDeclaredByOneClassAndHadByItsSubclassesAndImplementations() {
        final var derived = classInfo(Derived.class);
        assertThat(derived.hasDeclaredMethod("derivedMethod")).isTrue();
        assertThat(derived.hasDeclaredMethod("baseMethod")).isFalse();
        assertThat(derived.hasDeclaredMethod("noSuchMethod")).isFalse();

        assertThat(derived.hasMethod("derivedMethod")).isTrue();
        assertThat(derived.hasMethod("baseMethod")).isTrue();
        assertThat(derived.hasMethod("noSuchMethod")).isFalse();

        // A method is also inherited from an interface, which is why the subclass can implement it
        assertThat(classInfo(Interface.class).hasDeclaredMethod("interfaceMethod")).isTrue();
        assertThat(derived.hasMethod("interfaceMethod")).isTrue();

        // The superclass does not have the methods of its subclasses
        assertThat(classInfo(Base.class).hasMethod("derivedMethod")).isFalse();
    }

    /**
     * A class declares a method with an annotation if one of its own methods carries it, and has a method with an
     * annotation if one of the methods it inherits carries it too.
     */
    @Test
    public void aMethodAnnotationIsFoundOnTheClassThatDeclaresTheMethodAndOnItsSubclasses() {
        final var derived = classInfo(Derived.class);
        assertThat(derived.hasDeclaredMethodAnnotation(PredicatesMethodAnnotation.class.getName())).isFalse();
        assertThat(derived.hasMethodAnnotation(PredicatesMethodAnnotation.class.getName())).isTrue();
        assertThat(derived.hasMethodAnnotation(PredicatesUnusedAnnotation.class.getName())).isFalse();

        final var base = classInfo(Base.class);
        assertThat(base.hasDeclaredMethodAnnotation(PredicatesMethodAnnotation.class.getName())).isTrue();
        assertThat(base.hasDeclaredMethodAnnotation(PredicatesUnusedAnnotation.class.getName())).isFalse();

        assertThat(derived.hasMethodAnnotation(PredicatesMethodAnnotation.class)).isTrue();
        assertThat(derived.hasMethodAnnotation(PredicatesUnusedAnnotation.class)).isFalse();
        assertThat(base.hasDeclaredMethodAnnotation(PredicatesMethodAnnotation.class)).isTrue();
        assertThat(base.hasDeclaredMethodAnnotation(PredicatesUnusedAnnotation.class)).isFalse();
    }

    /**
     * An annotation on a method parameter is found through the class that declares the method, and through its
     * subclasses.
     */
    @Test
    public void aMethodParameterAnnotationIsFoundOnTheClassThatDeclaresTheMethodAndOnItsSubclasses() {
        final var derived = classInfo(Derived.class);
        assertThat(derived.hasDeclaredMethodParameterAnnotation(PredicatesParameterAnnotation.class.getName()))
                .isFalse();
        assertThat(derived.hasMethodParameterAnnotation(PredicatesParameterAnnotation.class.getName())).isTrue();
        assertThat(derived.hasMethodParameterAnnotation(PredicatesUnusedAnnotation.class.getName())).isFalse();

        final var base = classInfo(Base.class);
        assertThat(base.hasDeclaredMethodParameterAnnotation(PredicatesParameterAnnotation.class.getName()))
                .isTrue();
        assertThat(base.hasDeclaredMethodParameterAnnotation(PredicatesUnusedAnnotation.class.getName())).isFalse();

        assertThat(derived.hasMethodParameterAnnotation(PredicatesParameterAnnotation.class)).isTrue();
        assertThat(derived.hasMethodParameterAnnotation(PredicatesUnusedAnnotation.class)).isFalse();
        assertThat(base.hasDeclaredMethodParameterAnnotation(PredicatesParameterAnnotation.class)).isTrue();
        assertThat(base.hasDeclaredMethodParameterAnnotation(PredicatesUnusedAnnotation.class)).isFalse();
    }

    /** A class extends its superclasses, and every class extends {@link Object}, but an interface does not. */
    @Test
    public void aClassExtendsItsSuperclasses() {
        final var derived = classInfo(Derived.class);
        assertThat(derived.extendsSuperclass(Base.class)).isTrue();
        assertThat(derived.extendsSuperclass(Base.class.getName())).isTrue();
        assertThat(derived.extendsSuperclass(Object.class)).isTrue();
        assertThat(derived.extendsSuperclass(Derived.class)).isFalse();

        // An interface has no superclass, not even Object
        assertThat(classInfo(Interface.class).extendsSuperclass(Object.class)).isFalse();
    }

    /** A class implements the interfaces of its superclasses as well as its own. */
    @Test
    public void aClassImplementsItsInterfaces() {
        final var derived = classInfo(Derived.class);
        assertThat(derived.implementsInterface(Interface.class)).isTrue();
        assertThat(derived.implementsInterface(Interface.class.getName())).isTrue();
        assertThat(derived.implementsInterface(UnimplementedInterface.class)).isFalse();

        assertThat(classInfo(Base.class).implementsInterface(Interface.class)).isFalse();
    }

    /** Asking whether a class implements something that is not an interface is a mistake, and is reported. */
    @Test
    public void aClassThatIsNotAnInterfaceIsRejected() {
        final var derived = classInfo(Derived.class);
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> derived.implementsInterface(Base.class))
                .withMessageContaining(Base.class.getName());
    }

    /** Asking whether a class has an annotation that is not an annotation type is a mistake, and is reported. */
    @Test
    public void aClassThatIsNotAnAnnotationIsRejected() {
        final var derived = classInfo(Derived.class);
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> derived.hasFieldAnnotation(uncheckedCast(Base.class)))
                .withMessageContaining(Base.class.getName());
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> derived.hasDeclaredFieldAnnotation(uncheckedCast(Base.class)))
                .withMessageContaining(Base.class.getName());
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> derived.hasMethodAnnotation(uncheckedCast(Base.class)))
                .withMessageContaining(Base.class.getName());
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> derived.hasDeclaredMethodAnnotation(uncheckedCast(Base.class)))
                .withMessageContaining(Base.class.getName());
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> derived.hasMethodParameterAnnotation(uncheckedCast(Base.class)))
                .withMessageContaining(Base.class.getName());
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> derived.hasDeclaredMethodParameterAnnotation(uncheckedCast(Base.class)))
                .withMessageContaining(Base.class.getName());
    }

    /**
     * Pass a class that is not an annotation type to a parameter that is declared to take an annotation type, which
     * is what a caller who has only a {@link Class} does by accident.
     *
     * @param cls
     *            the class.
     * @return the same class, typed as an annotation type.
     */
    @SuppressWarnings("unchecked")
    private static Class<java.lang.annotation.Annotation> uncheckedCast(final Class<?> cls) {
        return (Class<java.lang.annotation.Annotation>) cls;
    }
}
