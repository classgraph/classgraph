package io.github.classgraph.issues.issue897;

import static java.lang.annotation.ElementType.TYPE_USE;
import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.classgraph.AnnotationInfo;
import io.github.classgraph.AnnotationInfoList;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassRefTypeSignature;
import io.github.classgraph.MethodInfo;
import io.github.classgraph.TypeSignature;

/**
 * A type annotation on a parameter of a non-static inner class constructor must be attached to the source-declared
 * parameter, not to the compiler-generated leading enclosing-instance parameter -- and must not throw "Ran out of
 * nested types while trying to add type annotation".
 *
 * <p>
 * The {@code formal_parameter_index} of a type annotation is counted from the first parameter declared in source
 * code, but the constructor's type descriptor has an extra leading parameter for the enclosing instance. Since the
 * annotated parameter type ({@code Inner2}) is itself an inner class, the (mis-)applied annotation used to overflow
 * the nesting of the unrelated enclosing-instance parameter type.
 */
public class Issue897Test {
    /**
     * Inner class whose constructor has an annotated parameter that is itself an inner class.
     */
    private class Inner1 {
        @SuppressWarnings("unused")
        public Inner1(@Anno Inner2 i) {
        }
    }

    /** Other inner class, used as the (annotated) parameter type. */
    public class Inner2 {
    }

    /**
     * Generic inner class: its constructor has a generic {@code Signature}, so the implicit enclosing-instance
     * parameter is detected by comparing the descriptor and signature parameter counts. This exercises the
     * implicit-prefix strip/restore path, which previously threw {@link java.util.ConcurrentModificationException}
     * on a direct {@link MethodInfo#getTypeDescriptor()} call.
     */
    private class Inner3<T> {
        @SuppressWarnings("unused")
        public Inner3(@Anno List<T> list) {
        }
    }

    /** Type-use annotation. */
    @Target(TYPE_USE)
    @interface Anno {
    }

    /**
     * Collect the names of all type annotations on a type signature, including those on nested suffixes.
     */
    private static List<String> typeAnnotationNames(final TypeSignature typeSignature) {
        final List<String> names = new ArrayList<>();
        if (typeSignature != null) {
            final var baseAnnotations = typeSignature.getTypeAnnotationInfo();
            if (baseAnnotations != null) {
                for (final AnnotationInfo ai : baseAnnotations) {
                    names.add(ai.getName());
                }
            }
            if (typeSignature instanceof final ClassRefTypeSignature classRefTypeSignature) {
                final var suffixAnnotations = classRefTypeSignature.getSuffixTypeAnnotationInfo();
                if (suffixAnnotations != null) {
                    for (final AnnotationInfoList aiList : suffixAnnotations) {
                        if (aiList != null) {
                            for (final AnnotationInfo ai : aiList) {
                                names.add(ai.getName());
                            }
                        }
                    }
                }
            }
        }
        return names;
    }

    /**
     * Test that the type annotation is attached to the source-declared parameter of the {@code Inner1} constructor,
     * rather than crashing or being misattached to the compiler-generated enclosing-instance parameter.
     */
    @Test
    public void annotationOnInnerClassConstructor() {
        // Scan this package non-recursively, so that the nested-class relationship between Issue897Test and Inner2
        // can be resolved when placing the type annotation (which carries an INNER_TYPE type path), without pulling
        // in the compiler-specific fixture sub-packages (ecjenum, fieldcrash).
        try (var scanResult = new ClassGraph().enableClasspath()
                .acceptPackagesNonRecursive(Issue897Test.class.getPackage().getName()).ignoreClassVisibility()
                .enableMethodInfo().enableAnnotationInfo().scan()) {
            final var classInfo = scanResult.getClassInfo(Inner1.class.getName());
            final var methodInfo = classInfo.getDeclaredConstructorInfo().get(0);

            // Must not throw "Ran out of nested types while trying to add type annotation" (#897).
            methodInfo.getTypeSignatureOrTypeDescriptor();
            methodInfo.getTypeDescriptor();

            // The descriptor has a leading (implicit) enclosing-instance parameter, followed by the source-declared
            // parameter of type Inner2.
            final var parameterInfo = methodInfo.getParameterInfo();
            assertThat(parameterInfo).hasSize(2);

            final var annoName = Anno.class.getName();
            // The annotation must be attached to the Inner2 parameter (the source-declared parameter)...
            assertThat(typeAnnotationNames(parameterInfo.get(1).getTypeSignatureOrTypeDescriptor()))
                    .containsExactly(annoName);
            // ...and not to the compiler-generated enclosing-instance parameter.
            assertThat(typeAnnotationNames(parameterInfo.get(0).getTypeSignatureOrTypeDescriptor()))
                    .doesNotContain(annoName);
        }
    }

    /**
     * Test that a constructor of a <i>generic</i> inner class can be decorated without throwing. The implicit
     * enclosing-instance parameter is detected here via the descriptor/signature parameter-count difference, and a
     * direct {@link MethodInfo#getTypeDescriptor()} call previously threw
     * {@link java.util.ConcurrentModificationException} because the implicit-prefix strip/restore used a
     * {@link java.util.List#subList} view that was invalidated by mutation of the backing list.
     */
    @Test
    public void annotationOnGenericInnerClassConstructor() {
        try (var scanResult = new ClassGraph().enableClasspath()
                .acceptPackagesNonRecursive(Issue897Test.class.getPackage().getName()).ignoreClassVisibility()
                .enableMethodInfo().enableAnnotationInfo().scan()) {
            final var classInfo = scanResult.getClassInfo(Inner3.class.getName());
            final var methodInfo = classInfo.getDeclaredConstructorInfo().get(0);

            // Must not throw ConcurrentModificationException on a direct type-descriptor call (#897).
            methodInfo.getTypeDescriptor();
            methodInfo.getTypeSignatureOrTypeDescriptor();

            // The @Anno must be attached to the List parameter (the source-declared parameter), not the
            // compiler-generated enclosing-instance parameter.
            final var parameterInfo = methodInfo.getParameterInfo();
            final var listParam = parameterInfo.get(parameterInfo.size() - 1);
            assertThat(typeAnnotationNames(listParam.getTypeSignatureOrTypeDescriptor()))
                    .contains(Anno.class.getName());
        }
    }
}
