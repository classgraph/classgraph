package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import org.junit.jupiter.api.Test;

/**
 * {@link ClassInfo#getClassesWithMethodAnnotation()}, {@link ClassInfo#getClassesWithMethodParameterAnnotation()}
 * and {@link ClassInfo#getClassesWithFieldAnnotation()} add the subclasses that inherit an annotated field or
 * method. A private member is not inherited, also when its annotation is repeated (javac then stores the
 * repeated annotations in their {@link Repeatable} container annotation in the classfile).
 */
public class PrivateRepeatedMemberAnnotationTest {
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

    /**
     * Scan the fixture classes.
     *
     * @return the scan result
     */
    private static ScanResult scan() {
        return new ClassGraph().enableClassInfo()
                .acceptClasses(RepeatedContainer.class.getName(), Repeated.class.getName(), Base.class.getName(),
                        Sub.class.getName())
                .enableFieldInfo().enableMethodInfo().enableAnnotationInfo().ignoreFieldVisibility()
                .ignoreMethodVisibility().scan();
    }

    /** A private field, method or method parameter that carries a repeated annotation is not inherited. */
    @Test
    public void privateMembersWithRepeatedAnnotationAreNotInherited() {
        try (ScanResult scanResult = scan()) {
            assertThat(scanResult.getClassesWithMethodAnnotation(Repeated.class).getNames())
                    .containsExactly(Base.class.getName());
            assertThat(scanResult.getClassesWithFieldAnnotation(Repeated.class).getNames())
                    .containsExactly(Base.class.getName());
            assertThat(scanResult.getClassesWithMethodParameterAnnotation(Repeated.class).getNames())
                    .containsExactly(Base.class.getName());
        }
    }
}
