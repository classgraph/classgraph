package io.github.classgraph.issues.issue314;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

/**
 * Issue314.
 */
public class Issue314Test {
    /**
     * The Class A.
     */
    private static class A {
    }

    /**
     * The Class B.
     */
    private static class B extends A {
    }

    /**
     * Issue 314.
     */
    @Test
    public void issue314() {
        // Get URL base for overriding classpath (otherwise the JSON representation of
        // the ScanResult won't be
        // the same after the first and second deserialization, because
        // overrideClasspath is set by the first
        // serialization for consistency.)
        final var classfileURL = getClass().getClassLoader()
                .getResource(Issue314Test.class.getName().replace('.', '/') + ".class").toString();
        final var classpathBase = classfileURL.substring(0,
                classfileURL.length() - (Issue314Test.class.getName().length() + 6));
        try (var scanResult1 = new ClassGraph().overrideClasspath(classpathBase)
                .acceptPackages(Issue314Test.class.getPackage().getName()).enableAllInfo().scan()) {
            assertThat(scanResult1.getClassInfo(A.class.getName())).isNotNull();
            assertThat(scanResult1.getClassInfo(B.class.getName())).isNotNull();
            final var json1 = scanResult1.toJSON(2);
            assertThat(json1).isNotEmpty();
            try (var scanResult2 = ScanResult.fromJSON(scanResult1.toJSON())) {
                final var json2 = scanResult2.toJSON(2);
                assertThat(json1).isEqualTo(json2);
                assertThat(scanResult1.getSubclasses(A.class).getNames()).containsOnly(B.class.getName());
                assertThat(scanResult2.getSubclasses(A.class).getNames()).containsOnly(B.class.getName());
            }
        }
    }
}
