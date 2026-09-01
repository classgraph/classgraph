package io.github.classgraph.issues.issue897;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.classgraph.AnnotationInfo;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.MethodParameterInfo;

/**
 * The enum case from the discussion: for an enum constructor, a TYPE_USE annotation's
 * {@code formal_parameter_index} is counted from the first source-declared parameter, but the descriptor begins
 * with the synthetic {@code (String name, int ordinal)} parameters.
 *
 * <p>
 * Crucially, this is verified against a constructor compiled by <b>ecj</b>, which (unlike javac) does <b>not</b>
 * emit a {@code Signature} attribute for enum constructors. With no signature to diff against, the implicit prefix
 * count must be determined structurally (via {@code isEnum()}). The fixture {@code ecjenum/EnumWithAnnoCtor.class}
 * is checked in (see the {@code .java} alongside it for how to recompile). Before the fix the annotation was
 * silently misattached to the synthetic {@code String} parameter.
 */
public class Issue897EcjEnumTest {
    private static final String FIXTURE_PKG = Issue897EcjEnumTest.class.getPackage().getName() + ".ecjenum";
    private static final String ENUM_CLASS = FIXTURE_PKG + ".EnumWithAnnoCtor";
    private static final String ANNO = ENUM_CLASS + "$Anno";

    /**
     * Names of the base (non-nested-suffix) type annotations on a type signature, or empty if none.
     */
    private static List<String> baseTypeAnnotationNames(final MethodParameterInfo param) {
        final var annotations = param.getTypeSignatureOrTypeDescriptor().getTypeAnnotationInfo();
        return annotations == null ? List.of() : annotations.stream().map(AnnotationInfo::getName).toList();
    }

    /**
     * The annotation must land on the source-declared {@code Object} parameter (last), not on either of the
     * synthetic {@code (String, int)} parameters (first two).
     */
    @Test
    public void annotationOnEcjEnumConstructor() {
        try (var scanResult = new ClassGraph().enableClassInfo().enableClasspath().acceptPackages(FIXTURE_PKG) //
                .ignoreClassVisibility().ignoreMethodVisibility() // the enum constructor is private
                .enableMethodInfo().enableAnnotationInfo().scan()) {
            final var classInfo = scanResult.getClassInfo(ENUM_CLASS);
            assertThat(classInfo).isNotNull();
            assertThat(classInfo.isEnum()).isTrue();

            final var constructors = classInfo.getMethodAndConstructorInfo()
                    .filter(mi -> "<init>".equals(mi.getName()));
            assertThat(constructors).hasSize(1);
            final var constructor = constructors.get(0);

            // Must not throw (#897).
            constructor.getTypeSignatureOrTypeDescriptor();
            constructor.getTypeDescriptor();

            final var params = constructor.getParameterInfo();
            // descriptor is (String name, int ordinal, Object o)
            assertThat(params).hasSize(3);

            // The annotation is on the source-declared Object parameter...
            assertThat(baseTypeAnnotationNames(params.get(2))).containsExactly(ANNO);
            // ...and not on either synthetic prefix parameter.
            assertThat(baseTypeAnnotationNames(params.get(0))).doesNotContain(ANNO);
            assertThat(baseTypeAnnotationNames(params.get(1))).doesNotContain(ANNO);
        }
    }
}
