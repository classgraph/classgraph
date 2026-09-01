package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * {@link ClassInfo#toStringWithSimpleNames()} rendered the record components of a record class with
 * {@code useSimpleNames = false} hardcoded, so the component types were left fully qualified while the record's own
 * name, its superclass and its superinterfaces were simplified.
 */
public class RecordSimpleNamesTest {
    /** A record with a component whose type is a class, and one whose type is generic. */
    public record Rec(String name, List<Integer> values) {
    }

    /** Simple names are used for the record components too, not just for the record name. */
    @Test
    public void recordComponentsUseSimpleNames() {
        try (var scanResult = new ClassGraph().enableClasspath()
                .acceptPackagesNonRecursive(RecordSimpleNamesTest.class.getPackage().getName()).enableClassInfo()
                .enableFieldInfo().enableMethodInfo().enableAnnotationInfo()
                .enableStaticFinalFieldConstantInitializerValues().ignoreClassVisibility().ignoreFieldVisibility()
                .ignoreMethodVisibility().scan()) {
            final var rec = scanResult.getClassInfo(Rec.class.getName());
            assertThat(rec).isNotNull();
            assertThat(rec.toStringWithSimpleNames())
                    .isEqualTo("public static final record Rec(String name, List<Integer> values)");
            assertThat(rec.toString()).isEqualTo("public static final record " + Rec.class.getName()
                    + "(java.lang.String name, java.util.List<java.lang.Integer> values)");
        }
    }
}
