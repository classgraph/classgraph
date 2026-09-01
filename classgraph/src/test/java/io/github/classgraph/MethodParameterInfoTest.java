package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.tools.ToolProvider;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link MethodParameterInfo} exposes the four sources of per-parameter metadata in a classfile: the parameter
 * types from the method type signature and type descriptor, and the names, modifiers and annotations from the
 * {@code MethodParameters} and parameter annotation attributes.
 */
public class MethodParameterInfoTest {
    /** An annotation that can be placed on a method parameter. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    public @interface Param {
        /**
         * The annotation value.
         *
         * @return the annotation value.
         */
        String value();
    }

    /** The class whose method parameters these tests inspect. */
    public static class Fixture {
        /** A non-static inner class, whose constructor has a mandated enclosing instance parameter. */
        public class Inner {
            /**
             * Constructor.
             *
             * @param x
             *            a final parameter.
             */
            public Inner(final int x) {
                // Empty
            }
        }

        /** An enum, whose constructor has two synthetic parameters. */
        public enum Enm {
            /** The only enum constant. */
            VALUE;

            /** Constructor. */
            Enm() {
                // Empty
            }
        }

        /**
         * A generic variadic method.
         *
         * @param named
         *            an annotated final parameter.
         * @param generic
         *            a parameter with a generic type.
         * @param variadic
         *            the variadic parameter.
         */
        public void generic(@Param("annotated") final String named, final List<String> generic,
                final int... variadic) {
            // Empty
        }

        /**
         * A non-generic method with an array parameter, which is not variadic.
         *
         * @param array
         *            an array parameter.
         */
        public void nonGeneric(final int[] array) {
            // Empty
        }
    }

    /** The name of the class compiled without {@code -parameters} by {@link #compileClassWithoutParameterNames}. */
    private static final String NO_PARAM_NAMES_CLASS = "com.xyz.noparamnames.NoParameterNames";

    /** The directory containing the class compiled without {@code -parameters}. */
    private static Path noParamNamesDir;

    /**
     * Compile a class <i>without</i> the {@code -parameters} flag, so that its classfile has no
     * {@code MethodParameters} attribute. (The build compiles the test sources <i>with</i> {@code -parameters}, so
     * this cannot be a class in this file.)
     *
     * @param tempDir
     *            the temporary directory to compile into.
     * @throws IOException
     *             if the source file could not be written.
     */
    @BeforeAll
    public static void compileClassWithoutParameterNames(@TempDir final Path tempDir) throws IOException {
        noParamNamesDir = tempDir;
        final var src = tempDir.resolve("NoParameterNames.java");
        Files.writeString(src, """
                package com.xyz.noparamnames;

                public class NoParameterNames {
                    public void method(String arg) {
                    }
                }
                """);
        final var compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("the tests must be run on a JDK, not a JRE").isNotNull();
        final var exitCode = compiler.run(null, null, null, "-d", tempDir.toString(), src.toString());
        assertThat(exitCode).as("javac exit code").isZero();
    }

    /**
     * Scan the fixture classes.
     *
     * @param enableAnnotationInfo
     *            whether to call {@link ClassGraph#enableAnnotationInfo()}.
     * @return the scan result.
     */
    private static ScanResult scanFixture(final boolean enableAnnotationInfo) {
        final var classGraph = new ClassGraph().enableClassInfo().enableClasspath().enableMethodInfo()
                .ignoreMethodVisibility()
                .acceptClasses(Fixture.class.getName(), Fixture.Inner.class.getName(), Fixture.Enm.class.getName());
        return (enableAnnotationInfo ? classGraph.enableAnnotationInfo() : classGraph).scan();
    }

    /**
     * Get the parameters of the one method with the given name in the given class.
     *
     * @param scanResult
     *            the scan result.
     * @param cls
     *            the class.
     * @param methodName
     *            the method name, or {@code "<init>"} for the constructor.
     * @return the method's parameters.
     */
    private static List<MethodParameterInfo> paramsOf(final ScanResult scanResult, final Class<?> cls,
            final String methodName) {
        final var classInfo = scanResult.getClassInfo(cls.getName());
        assertThat(classInfo).as("ClassInfo for " + cls.getName()).isNotNull();
        final var methods = "<init>".equals(methodName) ? classInfo.getConstructorInfo()
                : classInfo.getMethodInfo(methodName);
        assertThat(methods).as("methods named " + methodName).hasSize(1);
        return methods.get(0).getParameterInfo();
    }

    /** Each parameter knows the method that declares it, and its index in that method's parameter list. */
    @Test
    public void aParameterKnowsItsMethodAndItsIndex() {
        try (var scanResult = scanFixture(/* enableAnnotationInfo = */ true)) {
            final var params = paramsOf(scanResult, Fixture.class, "generic");
            assertThat(params).hasSize(3);
            for (var i = 0; i < params.size(); i++) {
                assertThat(params.get(i).getIndex()).isEqualTo(i);
                assertThat(params.get(i).getMethodInfo().getName()).isEqualTo("generic");
            }
        }
    }

    /** Parameter names and the {@code final} modifier are read from the {@code MethodParameters} attribute. */
    @Test
    public void namesAndModifiersAreReadFromTheClassfile() {
        try (var scanResult = scanFixture(/* enableAnnotationInfo = */ true)) {
            final var params = paramsOf(scanResult, Fixture.class, "generic");
            assertThat(params).extracting(MethodParameterInfo::getName).containsExactly("named", "generic",
                    "variadic");

            final var named = params.get(0);
            assertThat(named.isFinal()).isTrue();
            assertThat(named.isSynthetic()).isFalse();
            assertThat(named.isMandated()).isFalse();
            assertThat(named.getModifiers()).isEqualTo(Modifier.FINAL);
            assertThat(named.getModifiersString()).isEqualTo("final ");
        }
    }

    /** The enclosing instance parameter of a non-static inner class constructor is mandated. */
    @Test
    public void theEnclosingInstanceParameterOfAnInnerClassConstructorIsMandated() {
        try (var scanResult = scanFixture(/* enableAnnotationInfo = */ true)) {
            final var params = paramsOf(scanResult, Fixture.Inner.class, "<init>");
            assertThat(params).hasSize(2);

            final var enclosingInstance = params.get(0);
            assertThat(enclosingInstance.getName()).isEqualTo("this$0");
            assertThat(enclosingInstance.isMandated()).isTrue();
            assertThat(enclosingInstance.isSynthetic()).isFalse();
            assertThat(enclosingInstance.isFinal()).isTrue();
            assertThat(enclosingInstance.getModifiersString()).isEqualTo("final mandated ");

            assertThat(params.get(1).getName()).isEqualTo("x");
            assertThat(params.get(1).isMandated()).isFalse();
        }
    }

    /** The name and ordinal parameters of an enum constructor are synthetic. */
    @Test
    public void theImplicitParametersOfAnEnumConstructorAreSynthetic() {
        try (var scanResult = scanFixture(/* enableAnnotationInfo = */ true)) {
            final var params = paramsOf(scanResult, Fixture.Enm.class, "<init>");
            assertThat(params).hasSize(2);
            for (final var param : params) {
                assertThat(param.isSynthetic()).isTrue();
                assertThat(param.isMandated()).isFalse();
                assertThat(param.getModifiersString()).isEqualTo("synthetic ");
            }
        }
    }

    /**
     * The type signature carries generic type information, the type descriptor does not, and only a generic method
     * has a type signature at all.
     */
    @Test
    public void theTypeSignatureIsGenericAndTheTypeDescriptorIsNot() {
        try (var scanResult = scanFixture(/* enableAnnotationInfo = */ true)) {
            final var generic = paramsOf(scanResult, Fixture.class, "generic").get(1);
            assertThat(generic.getTypeSignature()).hasToString("java.util.List<java.lang.String>");
            assertThat(generic.getTypeDescriptor()).hasToString("java.util.List");
            assertThat(generic.getTypeSignatureOrTypeDescriptor()).isSameAs(generic.getTypeSignature());

            // A non-generic method has no type signature, so the type descriptor is used instead
            final var nonGeneric = paramsOf(scanResult, Fixture.class, "nonGeneric").get(0);
            assertThat(nonGeneric.getTypeSignature()).isNull();
            assertThat(nonGeneric.getTypeDescriptor()).hasToString("int[]");
            assertThat(nonGeneric.getTypeSignatureOrTypeDescriptor()).isSameAs(nonGeneric.getTypeDescriptor());
        }
    }

    /** Only the last parameter of a variadic method is variadic, even if another parameter has an array type. */
    @Test
    public void onlyTheVariadicParameterOfAVariadicMethodIsVariadic() {
        try (var scanResult = scanFixture(/* enableAnnotationInfo = */ true)) {
            assertThat(paramsOf(scanResult, Fixture.class, "generic")).extracting(MethodParameterInfo::isVarArgs)
                    .containsExactly(false, false, true);
            // An array parameter of a non-variadic method is not variadic
            assertThat(paramsOf(scanResult, Fixture.class, "nonGeneric").get(0).isVarArgs()).isFalse();
        }
    }

    /** Parameter annotations are listed, and require {@link ClassGraph#enableAnnotationInfo()}. */
    @Test
    public void parameterAnnotationsAreListed() {
        try (var scanResult = scanFixture(/* enableAnnotationInfo = */ true)) {
            final var params = paramsOf(scanResult, Fixture.class, "generic");
            final var annotationInfo = params.get(0).getAllAnnotationInfo();
            assertThat(annotationInfo).hasSize(1);
            assertThat(annotationInfo.get(0).getName()).isEqualTo(Param.class.getName());
            assertThat(annotationInfo.get(0).getParameterValues().getValue("value")).isEqualTo("annotated");
            assertThat(params.get(0).hasAnnotation(Param.class)).isTrue();

            // A parameter with no annotations has an empty annotation list
            assertThat(params.get(1).getAllAnnotationInfo()).isEmpty();
        }
    }

    /** Reading parameter annotations without {@link ClassGraph#enableAnnotationInfo()} throws. */
    @Test
    public void readingParameterAnnotationsRequiresAnnotationInfoToBeEnabled() {
        try (var scanResult = scanFixture(/* enableAnnotationInfo = */ false)) {
            final var param = paramsOf(scanResult, Fixture.class, "generic").get(0);
            assertThatIllegalStateException().isThrownBy(param::getAllAnnotationInfo)
                    .withMessageContaining("enableAnnotationInfo");
        }
    }

    /** A parameter is rendered with its annotations, modifiers, type and name. */
    @Test
    public void aParameterIsRenderedWithItsAnnotationsModifiersTypeAndName() {
        try (var scanResult = scanFixture(/* enableAnnotationInfo = */ true)) {
            final var params = paramsOf(scanResult, Fixture.class, "generic");

            assertThat(params.get(0))
                    .hasToString("@" + Param.class.getName() + "(\"annotated\") final java.lang.String named");
            assertThat(params.get(0).toStringWithSimpleNames())
                    .isEqualTo("@Param(\"annotated\") final String named");

            assertThat(params.get(1)).hasToString("final java.util.List<java.lang.String> generic");

            // The variadic parameter has the array type int[], but is rendered as "int..."
            assertThat(params.get(2)).hasToString("final int... variadic");

            // An array parameter of a non-variadic method is rendered as an array
            assertThat(paramsOf(scanResult, Fixture.class, "nonGeneric").get(0)).hasToString("final int[] array");
        }
    }

    /**
     * Parameters are equal if they have the same declaring method, index, name, modifiers, type and annotations.
     */
    @Test
    public void parametersAreComparedByTheirContents() {
        try (var scanResult = scanFixture(/* enableAnnotationInfo = */ true);
                var scanResult2 = scanFixture(/* enableAnnotationInfo = */ true)) {
            final var params = paramsOf(scanResult, Fixture.class, "generic");
            final var sameParams = paramsOf(scanResult2, Fixture.class, "generic");

            final var param = params.get(0);
            final var sameParam = sameParams.get(0);
            assertThat(sameParam).isNotSameAs(param).isEqualTo(param).hasSameHashCodeAs(param);
            assertThat(param).isEqualTo(param).isNotEqualTo(params.get(1)).isNotEqualTo(null)
                    .isNotEqualTo(param.toString());
        }
    }

    /**
     * A class compiled without {@code -parameters} has no {@code MethodParameters} attribute, so its parameters
     * have no name and no modifiers.
     */
    @Test
    public void aClassCompiledWithoutParameterNamesHasUnnamedParameters() {
        try (var scanResult = new ClassGraph().enableClassInfo().enableMethodInfo()
                .enableClasspathEntries(noParamNamesDir.toString()).acceptClasses(NO_PARAM_NAMES_CLASS).scan()) {
            final var classInfo = scanResult.getClassInfo(NO_PARAM_NAMES_CLASS);
            assertThat(classInfo).isNotNull();
            final var params = classInfo.getMethodInfo("method").get(0).getParameterInfo();
            assertThat(params).hasSize(1);

            final var param = params.get(0);
            assertThat(param.getName()).isNull();
            assertThat(param.getModifiers()).isZero();
            assertThat(param.getModifiersString()).isEmpty();
            assertThat(param.isFinal()).isFalse();
            assertThat(param.isSynthetic()).isFalse();
            assertThat(param.isMandated()).isFalse();
            assertThat(param).hasToString("java.lang.String _unnamed_param");
        }
    }
}
