package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.Serial;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.Test;

/**
 * Tests that {@code toString()} renders values the way the JDK renders the same values, i.e. in Java source syntax.
 */
public class ToStringRenderingTest {
    /** An annotation with one parameter of each kind that has its own source syntax. */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Values {
        /** @return an array of strings. */
        String[] strings();

        /** @return an array of chars. */
        char[] chars();

        /** @return a string needing escaping. */
        String str();

        /** @return a char needing escaping. */
        char chr();

        /** @return a byte. */
        byte byteVal();

        /** @return a short. */
        short shortVal();

        /** @return a long. */
        long longVal();

        /** @return a float. */
        float floatVal();

        /** @return a double. */
        double doubleVal();

        /** @return a float NaN. */
        float nan();

        /** @return a positive double infinity. */
        double inf();
    }

    /** An annotation that can be applied both to a parameter and to the type of a parameter. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ ElementType.PARAMETER, ElementType.TYPE_USE })
    public @interface Tu {
    }

    /** A class annotated with one value of each kind. */
    @Values(strings = { "a", "b" }, chars = { 'a', '\n' }, str = "\t\\\"\u00e9", chr = '\'', byteVal = 1,
            shortVal = 2, longVal = 4, floatVal = 1.5f, doubleVal = 2.5, nan = Float.NaN,
            inf = Double.POSITIVE_INFINITY)
    public static class Annotated {
    }

    /** A class with a constructor, a variadic method, and a method with an annotated parameter type. */
    public static class Methods {
        /** A no-arg constructor. */
        public Methods() {
        }

        /**
         * A variadic method.
         *
         * @param first
         *            the first parameter
         * @param rest
         *            the variadic parameter
         */
        public void varArgsMethod(final String first, final int... rest) {
        }

        /**
         * A method with an annotated parameter type.
         *
         * @param a
         *            the annotated parameter
         */
        public void annotatedParamMethod(final @Tu String a) {
        }
    }

    /** Constants whose values need escaping when rendered as Java literals. */
    public static class Constants {
        /** A string containing a newline. */
        public static final String NEWLINE = "a\nb";

        /** A tab character. */
        public static final char TAB = '\t';
    }

    /** A non-generic interface, so that {@link Parent} has an implements clause of its own. */
    public interface Marker {
    }

    /** A second non-generic interface, so that {@link Child}'s implements clause names a different one. */
    public interface Tag {
    }

    /** A non-generic superclass with modifiers, a superclass of its own, and an implements clause of its own. */
    public abstract static class Parent extends Exception implements Marker {
        /** serialVersionUID. */
        @Serial
        private static final long serialVersionUID = 1L;
    }

    /** A non-generic class whose extends and implements clauses both name a class that has clauses of its own. */
    public static class Child extends Parent implements Tag {
        /** serialVersionUID. */
        @Serial
        private static final long serialVersionUID = 1L;
    }

    /** Scan this test's own package. */
    private static ScanResult scan() {
        return new ClassGraph().enableClasspath()
                .acceptPackagesNonRecursive(ToStringRenderingTest.class.getPackage().getName()).enableAllInfo()
                .scan();
    }

    /** Annotation parameter values are rendered in the Java source syntax for their type. */
    @Test
    public void annotationParameterValuesUseJavaSourceSyntax() {
        try (var scanResult = scan()) {
            final var params = scanResult.getClassInfo(Annotated.class.getName()).getAllAnnotationInfo()
                    .get(Values.class.getName()).getParameterValues();
            // Elements of an array-valued parameter are quoted and escaped, just like scalar values
            assertThat(params.get("strings")).hasToString("strings={\"a\", \"b\"}");
            assertThat(params.get("chars")).hasToString("chars={'a', '\\n'}");
            // Tab, backslash, double quote and a non-ASCII character, escaped as the JDK escapes them
            assertThat(params.get("str")).hasToString("str=\"\\t\\\\\\\"\\u00e9\"");
            assertThat(params.get("chr")).hasToString("chr='\\''");
            // Numeric values carry the source syntax for their type, since an annotation parameter value is
            // rendered without its type
            assertThat(params.get("byteVal")).hasToString("byteVal=(byte)0x01");
            assertThat(params.get("shortVal")).hasToString("shortVal=2");
            assertThat(params.get("longVal")).hasToString("longVal=4L");
            assertThat(params.get("floatVal")).hasToString("floatVal=1.5f");
            assertThat(params.get("doubleVal")).hasToString("doubleVal=2.5");
            assertThat(params.get("nan")).hasToString("nan=0.0f/0.0f");
            assertThat(params.get("inf")).hasToString("inf=1.0/0.0");
        }
    }

    /** A constructor is named after the class it constructs, not {@code <init>}. */
    @Test
    public void constructorIsNamedAfterItsClass() {
        try (var scanResult = scan()) {
            assertThat(scanResult.getClassInfo(Methods.class.getName()).getConstructorInfo().get(0))
                    .hasToString("public " + Methods.class.getName() + "()");
        }
    }

    /** The variadic parameter of a variadic method is rendered as {@code T...}, not {@code T[]}. */
    @Test
    public void variadicParameterIsRenderedWithEllipsis() {
        try (var scanResult = scan()) {
            final var methodInfo = scanResult.getClassInfo(Methods.class.getName())
                    .getDeclaredMethodInfo("varArgsMethod").get(0);
            assertThat(methodInfo.toString()).contains("int...").doesNotContain("int[]");
            final var paramInfo = methodInfo.getParameterInfo();
            assertThat(paramInfo.get(0).getIndex()).isZero();
            assertThat(paramInfo.get(0).isVarArgs()).isFalse();
            assertThat(paramInfo.get(1).getIndex()).isEqualTo(1);
            assertThat(paramInfo.get(1).isVarArgs()).isTrue();
            assertThat(paramInfo.get(1).toString()).contains("int...").doesNotContain("int[]");
        }
    }

    /** A {@code TYPE_USE} annotation on a parameter type is listed once, not once per applicable target. */
    @Test
    public void parameterTypeAnnotationIsListedOnce() {
        try (var scanResult = scan()) {
            final var paramInfo = scanResult.getClassInfo(Methods.class.getName())
                    .getDeclaredMethodInfo("annotatedParamMethod").get(0).getParameterInfo().get(0);
            final var rendered = paramInfo.toString();
            final var annotationName = "@" + Tu.class.getName();
            final var firstIdx = rendered.indexOf(annotationName);
            assertThat(firstIdx).isNotNegative();
            assertThat(rendered.indexOf(annotationName, firstIdx + 1)).isEqualTo(-1);
        }
    }

    /** Field constant initializer values are escaped as Java literals. */
    @Test
    public void fieldConstantInitializerValuesAreEscaped() {
        try (var scanResult = scan()) {
            final var constants = scanResult.getClassInfo(Constants.class.getName());
            assertThat(constants.getFieldInfo("NEWLINE").toString()).endsWith("NEWLINE = \"a\\nb\"");
            assertThat(constants.getFieldInfo("TAB").toString()).endsWith("TAB = '\\t'");
        }
    }

    /** A {@link PackageInfo} names itself as a package, as {@link Package#toString()} does. */
    @Test
    public void packageInfoNamesItselfAsAPackage() {
        try (var scanResult = scan()) {
            final var packageName = ToStringRenderingTest.class.getPackage().getName();
            assertThat(scanResult.getPackageInfo(packageName)).hasToString("package " + packageName);
        }
    }

    /**
     * An extends or implements clause names only the class, as a Java source declaration does. The named class's
     * own modifiers, class type, type parameters, record parameters and supertypes belong to its own declaration.
     */
    @Test
    public void anExtendsOrImplementsClauseNamesOnlyTheClass() {
        try (var scanResult = scan()) {
            assertThat(scanResult.getClassInfo(Child.class.getName()))
                    .hasToString("public static class " + Child.class.getName() + " extends "
                            + Parent.class.getName() + " implements " + Tag.class.getName());
        }
    }

    /** A {@link ModuleInfo} names itself as a module, as {@link Module#toString()} does. */
    @Test
    public void moduleInfoNamesItselfAsAModule() {
        try (var scanResult = new ClassGraph().enableClassInfo().enableSystemJars().enableSystemModules()
                .acceptModules("java.base").acceptPackagesNonRecursive("java.lang").scan()) {
            assertThat(scanResult.getModuleInfo("java.base")).hasToString("module java.base");
        }
    }
}
