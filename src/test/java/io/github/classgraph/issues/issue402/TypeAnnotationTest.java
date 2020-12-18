package io.github.classgraph.issues.issue402;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ScanResult;

/**
 * TypeAnnotationTest.
 */
class TypeAnnotationTest {
    /** Annotation A. */
    @Retention(RetentionPolicy.RUNTIME)
    private static @interface A {
    }

    /** Annotation B. */
    @Retention(RetentionPolicy.RUNTIME)
    private static @interface B {
    }

    /** Annotation C. */
    @Retention(RetentionPolicy.RUNTIME)
    private static @interface C {
    }

    /** Annotation D. */
    @Retention(RetentionPolicy.RUNTIME)
    private static @interface D {
    }

    /** Annotation E. */
    @Retention(RetentionPolicy.RUNTIME)
    private static @interface E {
    }

    /** Annotation F. */
    @Retention(RetentionPolicy.RUNTIME)
    private static @interface F {
    }

    /** Annotation G. */
    @Retention(RetentionPolicy.RUNTIME)
    private static @interface G {
    }

    /** Annotation H. */
    @Retention(RetentionPolicy.RUNTIME)
    private static @interface H {
    }

    /** Annotation I. */
    @Retention(RetentionPolicy.RUNTIME)
    private static @interface I {
    }

    @A
    Map<@B ? extends @C String, @D List<@E Object>> map;

    @I
    String @F [] @G [] @H [] arr;

    @A
    List<@B Comparable<@F Object @C [] @D [] @E []>> comparable;

    @A
    Outer.@B Middle.@C Inner1 inner1;

    Outer.@A MiddleStatic.@B Inner2 inner2;

    Outer.MiddleStatic.@A InnerStatic inner3;

    Outer.MiddleGeneric<@A Foo.@B Bar>.InnerGeneric<@D String @C []> inner4;

    static class X2 {
        class Y2 {
            class Z2 {
            }
        }
    }

    static class X3 {
        interface Y3 {
            class Z3 {
            }
        }
    }

    static class X4 {
        static class Y4 {
            class Z4 {
            }
        }
    }

    List<@A X.@B Y.@C Z> xyz;

    List<@A X2.@B Y2.@C Z2> xyz2;

    List<X3.Y3.@A Z3> xyz3;

    List<X3.Y3.@A Z3> xyz4;

    private static String shortNames(final Object type) {
        return type.toString().replace(TypeAnnotationTest.class.getName() + ".", "")
                .replace(TypeAnnotationTest.class.getName() + "$", "")
                .replace(TypeAnnotationTest.class.getPackage().getName() + ".", "").replace("java.lang.", "")
                .replace("java.util.", "");
    }

    /** Test type annotations. */
    @Test
    void fieldsWithTypeAnnotations() {
        try (ScanResult scanResult = new ClassGraph()
                .acceptPackages(TypeAnnotationTest.class.getPackage().getName()).enableAllInfo().scan()) {
            final ClassInfo classInfo = scanResult.getClassInfo(TypeAnnotationTest.class.getName());

            assertThat(shortNames(classInfo.getFieldInfo("map")))
                    .isEqualTo("@A Map<@B ? extends @C String, @D List<@E Object>> map");

            assertThat(shortNames(classInfo.getFieldInfo("arr"))).isEqualTo("@I String @F [] @G [] @H [] arr");

            assertThat(shortNames(classInfo.getFieldInfo("comparable")))
                    .isEqualTo("@A List<@B Comparable<@F Object @C [] @D [] @E []>> comparable");

            assertThat(shortNames(classInfo.getFieldInfo("inner1")))
                    .isEqualTo("@A Outer.@B Middle.@C Inner1 inner1");

            assertThat(shortNames(classInfo.getFieldInfo("inner2")))
                    .isEqualTo("Outer.@A MiddleStatic.@B Inner2 inner2");

            assertThat(shortNames(classInfo.getFieldInfo("inner3")))
                    .isEqualTo("Outer.MiddleStatic.@A InnerStatic inner3");

            assertThat(shortNames(classInfo.getFieldInfo("inner4")))
                    .isEqualTo("Outer.MiddleGeneric<@A Foo.@B Bar>.InnerGeneric<@D String @C []> inner4");

            assertThat(shortNames(classInfo.getFieldInfo("xyz"))).isEqualTo("List<@A X.@B Y.@C Z> xyz");

            assertThat(shortNames(classInfo.getFieldInfo("xyz2"))).isEqualTo("List<@A X2.@B Y2.@C Z2> xyz2");

            assertThat(shortNames(classInfo.getFieldInfo("xyz3"))).isEqualTo("List<X3.Y3.@A Z3> xyz3");

            assertThat(shortNames(classInfo.getFieldInfo("xyz4"))).isEqualTo("List<X3.Y3.@A Z3> xyz4");
        }
    }
}
