package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

/**
 * {@link ClassInfo#getClassesWithMethodAnnotation()}, {@link ClassInfo#getClassesWithMethodParameterAnnotation()}
 * and {@link ClassInfo#getClassesWithFieldAnnotation()} add the classes that inherit an annotated field or method:
 * the subclasses of the declaring class, and, if the declaring class is an interface, the classes that implement
 * it, their subclasses, and its subinterfaces. A private member is not inherited, and nor is a static method of an
 * interface.
 */
public class InheritedMemberAnnotationTest {
    /** A method annotation. */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface OnMethod {
    }

    /** A method parameter annotation. */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface OnParam {
    }

    /** A field annotation. */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface OnField {
    }

    /** A method annotation that is placed only on a static method. */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface OnStaticMethod {
    }

    /** A method parameter annotation that is placed only on a parameter of a static method. */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface OnStaticMethodParam {
    }

    /** The container of {@link Repeated}. */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface RepeatedContainer {
        /**
         * The repeats.
         *
         * @return the repeats.
         */
        Repeated[] value();
    }

    /** A repeatable annotation that is placed only on private members. */
    @Repeatable(RepeatedContainer.class)
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Repeated {
    }

    /** An interface with an annotated default method, static method and field. */
    public interface Iface {
        /** An annotated field. */
        @OnField
        int FIELD = 1;

        /**
         * An annotated default method with an annotated parameter.
         *
         * @param x
         *            a parameter
         */
        @OnMethod
        default void defaultMethod(@OnParam final int x) {
        }

        /**
         * An annotated static method with an annotated parameter.
         *
         * @param x
         *            a parameter
         */
        @OnStaticMethod
        static void staticMethod(@OnStaticMethodParam final int x) {
        }
    }

    /** A subinterface. */
    public interface SubIface extends Iface {
    }

    /** A class that implements the interface. */
    public static class Impl implements Iface {
    }

    /** A subclass of the class that implements the interface. */
    public static class ImplSub extends Impl {
    }

    /** A class with private members that carry a repeated annotation. */
    public static class Base {
        /** A private field with a repeated annotation. */
        @Repeated
        @Repeated
        private int privateField;

        /**
         * A private method with a repeated annotation, and a parameter with a repeated annotation.
         *
         * @param x
         *            a parameter
         */
        @Repeated
        @Repeated
        private void privateMethod(@Repeated @Repeated final int x) {
        }
    }

    /** A subclass, which does not inherit the private members. */
    public static class Sub extends Base {
    }

    /** The fixture classes. */
    private static final Class<?>[] CLASSES = { OnMethod.class, OnParam.class, OnField.class, OnStaticMethod.class,
            OnStaticMethodParam.class, RepeatedContainer.class, Repeated.class, Iface.class, SubIface.class,
            Impl.class, ImplSub.class, Base.class, Sub.class };

    /**
     * Scan the fixture classes.
     *
     * @return the scan result
     */
    private static ScanResult scan() {
        return new ClassGraph().enableClassInfo().enableClasspath()
                .acceptClasses(Arrays.stream(CLASSES).map(Class::getName).toArray(String[]::new)).enableFieldInfo()
                .enableMethodInfo().enableAnnotationInfo().ignoreFieldVisibility().ignoreMethodVisibility().scan();
    }

    /** The names of the interface, its subinterface, its implementing class and that class's subclass. */
    private static final String[] IFACE_HIERARCHY = { Iface.class.getName(), SubIface.class.getName(),
            Impl.class.getName(), ImplSub.class.getName() };

    /**
     * An annotated default method, a parameter of a default method, and a field of an interface are inherited by
     * the implementing classes, their subclasses and the subinterfaces.
     */
    @Test
    public void interfaceMembersAreInheritedByImplementersAndSubinterfaces() {
        try (var scanResult = scan()) {
            assertThat(scanResult.getClassesWithMethodAnnotation(OnMethod.class).getNames())
                    .containsExactlyInAnyOrder(IFACE_HIERARCHY);
            assertThat(scanResult.getClassesWithMethodParameterAnnotation(OnParam.class).getNames())
                    .containsExactlyInAnyOrder(IFACE_HIERARCHY);
            assertThat(scanResult.getClassesWithFieldAnnotation(OnField.class).getNames())
                    .containsExactlyInAnyOrder(IFACE_HIERARCHY);
            // Only the interface declares the members
            assertThat(scanResult.getClassesWithMethodAnnotation(OnMethod.class).directOnly().getNames())
                    .containsExactly(Iface.class.getName());
            // The list agrees with hasMethodAnnotation
            for (final String name : IFACE_HIERARCHY) {
                assertThat(scanResult.getClassInfo(name).hasMethodAnnotation(OnMethod.class)).as(name).isTrue();
            }
        }
    }

    /** A static method of an interface is not inherited. */
    @Test
    public void staticInterfaceMethodsAreNotInherited() {
        try (var scanResult = scan()) {
            assertThat(scanResult.getClassesWithMethodAnnotation(OnStaticMethod.class).getNames())
                    .containsExactly(Iface.class.getName());
            assertThat(scanResult.getClassesWithMethodParameterAnnotation(OnStaticMethodParam.class).getNames())
                    .containsExactly(Iface.class.getName());
        }
    }

    /** Forward lookup and its boolean convenience query also exclude noninheritable declarations. */
    @Test
    public void forwardMethodLookupDoesNotInheritStaticInterfaceOrPrivateAncestorMethods() {
        try (var scanResult = scan()) {
            final var iface = scanResult.getClassInfo(Iface.class.getName());
            assertThat(iface.getMethodInfo("staticMethod")).hasSize(1);
            assertThat(iface.hasMethod("staticMethod")).isTrue();
            for (final var type : new Class<?>[] { SubIface.class, Impl.class, ImplSub.class }) {
                final var info = scanResult.getClassInfo(type.getName());
                assertThat(info.getMethodInfo("staticMethod")).isEmpty();
                assertThat(info.getMethodInfo().getNames()).doesNotContain("staticMethod");
                assertThat(info.hasMethod("staticMethod")).isFalse();
                assertThat(info.hasMethodAnnotation(OnStaticMethod.class)).isFalse();
                assertThat(info.hasMethodParameterAnnotation(OnStaticMethodParam.class)).isFalse();
                assertThat(info.getMethodInfo("defaultMethod")).hasSize(1);
                // The JVM's public-method view independently confirms the interface-static rule.
                assertThat(Arrays.stream(type.getMethods()).map(method -> method.getName()))
                        .doesNotContain("staticMethod");
            }
            final var base = scanResult.getClassInfo(Base.class.getName());
            final var sub = scanResult.getClassInfo(Sub.class.getName());
            assertThat(base.getDeclaredMethodInfo("privateMethod")).hasSize(1);
            assertThat(sub.getMethodInfo("privateMethod")).isEmpty();
            assertThat(sub.hasMethod("privateMethod")).isFalse();
        }
    }

    /** A private field, method or method parameter that carries a repeated annotation is not inherited. */
    @Test
    public void privateMembersWithRepeatedAnnotationAreNotInherited() {
        try (var scanResult = scan()) {
            assertThat(scanResult.getClassesWithMethodAnnotation(Repeated.class).getNames())
                    .containsExactly(Base.class.getName());
            assertThat(scanResult.getClassesWithFieldAnnotation(Repeated.class).getNames())
                    .containsExactly(Base.class.getName());
            assertThat(scanResult.getClassesWithMethodParameterAnnotation(Repeated.class).getNames())
                    .containsExactly(Base.class.getName());
        }
    }
}
