package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link ClassInfo#getSimpleName()} returned the text after the last '$' of the class name whenever a name came
 * before it, so a class whose name ends in '$', such as every Scala {@code object}, had the simple name "". A '$'
 * at the start of a class name, as in {@code $AutoValue_Foo}, was already kept, and still is.
 */
public class ClassInfoSimpleNameTest {
    /**
     * A class whose name ends in '$', with a field and a method that have the class as their type.
     */
    public static class Reg$ {
        /** A field of the class's own type. */
        public Reg$ self;

        /**
         * A method that takes and returns the class's own type.
         *
         * @param r
         *            a value.
         * @return the value.
         */
        public Reg$ make(final Reg$ r) {
            return r;
        }
    }

    /** Only a '.', and a '$' that separates two names, end the part of the class name that is stripped. */
    @Test
    public void aDollarSignAtTheEndOrAtTheStartOfANameIsPartOfTheName() {
        assertThat(ClassInfo.getSimpleName("Reg$")).isEqualTo("Reg$");
        assertThat(ClassInfo.getSimpleName("a.b.Reg$")).isEqualTo("Reg$");
        assertThat(ClassInfo.getSimpleName("a.b.Either$LeftProjection$")).isEqualTo("LeftProjection$");
        assertThat(ClassInfo.getSimpleName("$Foo")).isEqualTo("$Foo");
        assertThat(ClassInfo.getSimpleName("a.b.$AutoValue_Foo")).isEqualTo("$AutoValue_Foo");
        assertThat(ClassInfo.getSimpleName("a.b.$$A")).isEqualTo("$$A");
        assertThat(ClassInfo.getSimpleName("$")).isEqualTo("$");
        // A '$' that separates two names is still a separator
        assertThat(ClassInfo.getSimpleName("$Foo$Inner")).isEqualTo("Inner");
        assertThat(ClassInfo.getSimpleName("a.Outer$Node")).isEqualTo("Node");
        assertThat(ClassInfo.getSimpleName("a.Outer$1")).isEqualTo("1");
        assertThat(ClassInfo.getSimpleName("a.Foo$$anon$1")).isEqualTo("1");
        assertThat(ClassInfo.getSimpleName("a.b.C")).isEqualTo("C");
        assertThat(ClassInfo.getSimpleName("C")).isEqualTo("C");
    }

    /** The simple names of a class, and of the types of its members, keep a '$' at the end of the class name. */
    @Test
    public void simpleNamesKeepATrailingDollarSign() {
        try (var scanResult = new ClassGraph().enableClasspath().acceptClasses(Reg$.class.getName())
                .enableClassInfo().enableFieldInfo().enableMethodInfo().scan()) {
            final var classInfo = scanResult.getClassInfo(Reg$.class.getName());
            assertThat(classInfo).isNotNull();
            assertThat(classInfo.getSimpleName()).isEqualTo(Reg$.class.getSimpleName()).isEqualTo("Reg$");
            assertThat(classInfo.toStringWithSimpleNames()).isEqualTo("public static class Reg$");
            assertThat(classInfo.getFieldInfo("self").toStringWithSimpleNames()).isEqualTo("public Reg$ self");
            assertThat(classInfo.getMethodInfo("make").getSingleMethod("make").toStringWithSimpleNames())
                    .isEqualTo("public Reg$ make(final Reg$ r)");
        }
    }
}
