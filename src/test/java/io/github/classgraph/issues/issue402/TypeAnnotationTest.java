package io.github.classgraph.issues.issue402;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.ElementType;
import java.lang.annotation.Target;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ClassRefTypeSignature;
import io.github.classgraph.FieldInfo;
import io.github.classgraph.ScanResult;

/**
 * TypeAnnotationTest.
 */
class TypeAnnotationTest {
    @Target({ ElementType.FIELD, ElementType.TYPE_USE, ElementType.TYPE_PARAMETER })
    private static @interface A {
    }

    @Target({ ElementType.TYPE_USE, ElementType.TYPE_PARAMETER })
    private static @interface B {
    }

    @Target({ ElementType.TYPE_USE, ElementType.TYPE_PARAMETER })
    private static @interface C {
    }

    @Target({ ElementType.TYPE_USE, ElementType.TYPE_PARAMETER })
    private static @interface D {
    }

    @Target({ ElementType.TYPE_USE, ElementType.TYPE_PARAMETER })
    private static @interface E {
    }

    @Target({ ElementType.TYPE_USE, ElementType.TYPE_PARAMETER })
    private static @interface F {
    }

    @Target({ ElementType.TYPE_USE, ElementType.TYPE_PARAMETER })
    private static @interface G {
    }

    @Target({ ElementType.TYPE_USE, ElementType.TYPE_PARAMETER })
    private static @interface H {
    }

    @Target({ ElementType.TYPE_USE, ElementType.TYPE_PARAMETER })
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

    static class U {
    }

    interface V {
    }

    <@A T extends @B U> @D U t(@E final T t) {
        return null;
    }

    static class P<@A T extends @B U & @C V> {
        public void explicitReceiver(@F P<T> this) {
        }
    }

    @Target({ ElementType.TYPE_USE, ElementType.TYPE_PARAMETER })
    private static @interface Size {
        int max();
    }

    @A
    class Person {
        List<@Size(max = 50) String> emails;
    }

    /**
     * Convert class names to short names.
     *
     * @param type
     *            the type
     * @return the class names as short names
     */
    private static String shortNames(final Object type) {
        return type.toString().replace(TypeAnnotationTest.class.getName() + ".", "")
                .replace(TypeAnnotationTest.class.getName() + "$", "")
                .replace(TypeAnnotationTest.class.getPackage().getName() + ".", "").replace("java.lang.", "")
                .replace("java.util.", "");
    }

    /** Test field type annotations. */
    @Test
    void typeAnnotations() {
        try (ScanResult scanResult = new ClassGraph()
                .acceptPackages(TypeAnnotationTest.class.getPackage().getName()).enableAllInfo().scan()) {
            final ClassInfo classInfo = scanResult.getClassInfo(TypeAnnotationTest.class.getName());

            final FieldInfo mapField = classInfo.getFieldInfo("map");
            assertThat(shortNames(mapField)).isEqualTo("@A Map<@B ? extends @C String, @D List<@E Object>> map");
            assertThat(mapField.toStringWithSimpleNames())
                    .isEqualTo("@A Map<@B ? extends @C String, @D List<@E Object>> map");

            final FieldInfo arrField = classInfo.getFieldInfo("arr");
            assertThat(shortNames(arrField)).isEqualTo("@I String @F [] @G [] @H [] arr");
            assertThat(arrField.toStringWithSimpleNames()).isEqualTo("@I String @F [] @G [] @H [] arr");

            final FieldInfo comparableField = classInfo.getFieldInfo("comparable");
            assertThat(shortNames(comparableField))
                    .isEqualTo("@A List<@B Comparable<@F Object @C [] @D [] @E []>> comparable");
            assertThat(comparableField.toStringWithSimpleNames())
                    .isEqualTo("@A List<@B Comparable<@F Object @C [] @D [] @E []>> comparable");

            final FieldInfo inner1Field = classInfo.getFieldInfo("inner1");
            assertThat(shortNames(inner1Field)).isEqualTo("@A Outer$@B Middle$@C Inner1 inner1");
            assertThat(inner1Field.toStringWithSimpleNames()).isEqualTo("@A @C Inner1 inner1");

            assertThat(shortNames(classInfo.getFieldInfo("inner2")))
                    .isEqualTo("Outer$@A MiddleStatic$@B Inner2 inner2");

            assertThat(shortNames(classInfo.getFieldInfo("inner3")))
                    .isEqualTo("Outer$MiddleStatic$@A InnerStatic inner3");

            assertThat(shortNames(classInfo.getFieldInfo("inner4")))
                    .isEqualTo("Outer$MiddleGeneric<@A Foo$@B Bar>$InnerGeneric<@D String @C []> inner4");

            final FieldInfo xyzField = classInfo.getFieldInfo("xyz");
            assertThat(shortNames(xyzField)).isEqualTo("List<@A X$@B Y$@C Z> xyz");
            assertThat(xyzField.toStringWithSimpleNames()).isEqualTo("List<@C Z> xyz");

            assertThat(shortNames(classInfo.getFieldInfo("xyz2"))).isEqualTo("List<@A X2$@B Y2$@C Z2> xyz2");

            assertThat(shortNames(classInfo.getFieldInfo("xyz3"))).isEqualTo("List<X3$Y3$@A Z3> xyz3");

            assertThat(shortNames(classInfo.getFieldInfo("xyz4"))).isEqualTo("List<X3$Y3$@A Z3> xyz4");

            assertThat(shortNames(classInfo.getMethodInfo("t").get(0)))
                    .isEqualTo("<@A T extends @B U> @D U t(final @E T t)");

            assertThat(classInfo.getMethodInfo("t").get(0).toStringWithSimpleNames())
                    .isEqualTo("<@A T extends @B U> @D U t(final @E T t)");

            final ClassInfo personClassInfo = scanResult.getClassInfo(Person.class.getName());

            final FieldInfo emailsField = personClassInfo.getFieldInfo("emails");
            assertThat(shortNames(emailsField)).isEqualTo("List<@Size(max=50) String> emails");
            assertThat(emailsField.toStringWithSimpleNames()).isEqualTo("List<@Size(max=50) String> emails");

            assertThat(shortNames(((ClassRefTypeSignature) emailsField.getTypeSignatureOrTypeDescriptor())
                    .getTypeArguments().get(0).getTypeSignature().getTypeAnnotationInfo().get(0)))
                            .isEqualTo("@Size(max=50)");

            assertThat(shortNames(personClassInfo)).isEqualTo("@A class Person");
            assertThat(personClassInfo.toStringWithSimpleNames()).isEqualTo("@A class Person");

            final ClassInfo pClassInfo = scanResult.getClassInfo(P.class.getName());

            assertThat(shortNames(pClassInfo)).isEqualTo("static class P<@A T extends @B U & @C V>");

            assertThat(shortNames(pClassInfo.getMethodInfo("explicitReceiver").get(0)
                    .getTypeSignatureOrTypeDescriptor().getReceiverTypeAnnotationInfo().get(0))).isEqualTo("@F");

        }
    }
}
