package io.github.classgraph.issues.issue897;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.classgraph.AnnotationInfo;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.FieldInfo;

/**
 * Field hardening: {@code FieldInfo.getTypeDescriptor()} /
 * {@code getTypeSignature()} must not abort when an individual type annotation
 * cannot be matched to the field type. Compilers do not normally emit an
 * impossible field type-annotation path, so this is exercised with a synthetic
 * fixture ({@code fieldcrash/BadFieldTypePath.class}, generated with ASM) that
 * has:
 * <ul>
 * <li>a {@code String} field with a valid (empty-path) type annotation,
 * and</li>
 * <li>a {@code String} field with a bogus {@code [ARRAY]} type-annotation path
 * (invalid on a non-array type, of the same kind javac produces for bridge
 * methods in JDK-8385663).</li>
 * </ul>
 * Before the fix, reading the bogus field threw
 * {@code IllegalArgumentException: Bad typePathKind: 0}; the valid field's
 * annotation must still be applied.
 */
public class Issue897FieldTypeAnnotationTest {
    private static final String FIXTURE_PKG = //
            Issue897FieldTypeAnnotationTest.class.getPackage().getName() + ".fieldcrash";
    private static final String FIXTURE_CLASS = FIXTURE_PKG + ".BadFieldTypePath";
    private static final String ANNO = FIXTURE_PKG + ".FieldAnno";

    /** Names of the base type annotations on a field's type, or empty if none. */
    private static List<String> baseTypeAnnotationNames(final FieldInfo field) {
        final var typeSignature = field.getTypeSignatureOrTypeDescriptor();
        final var annotations = typeSignature == null ? null : typeSignature.getTypeAnnotationInfo();
        return annotations == null ? List.of() : annotations.stream().map(AnnotationInfo::getName).toList();
    }

    @Test
    public void unmatchableFieldTypeAnnotationIsSkipped() {
        try (var scanResult = new ClassGraph().acceptPackages(FIXTURE_PKG) //
                .ignoreClassVisibility().ignoreFieldVisibility() //
                .enableFieldInfo().enableAnnotationInfo().scan()) {
            final var classInfo = scanResult.getClassInfo(FIXTURE_CLASS);
            assertThat(classInfo).isNotNull();

            // The valid annotation is still applied.
            assertThat(baseTypeAnnotationNames(classInfo.getFieldInfo("validlyAnnotated"))).containsExactly(ANNO);

            // The bogus [ARRAY] path on a non-array type must be skipped rather than
            // throwing (#897).
            final var bogus = classInfo.getFieldInfo("bogusArrayPath");
            bogus.getTypeSignatureOrTypeDescriptor();
            bogus.getTypeDescriptor();
            assertThat(baseTypeAnnotationNames(bogus)).doesNotContain(ANNO);
        }
    }
}
