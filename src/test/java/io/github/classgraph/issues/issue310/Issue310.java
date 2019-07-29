package io.github.classgraph.issues.issue310;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

/**
 * Issue310.
 */
public class Issue310 {
    /** The Constant A. */
    static final double A = 3.0;

    /** The Constant B. */
    static final double B = -4.0;

    /** The Constant C. */
    static final double C = Double.NEGATIVE_INFINITY;

    /** The Constant D. */
    static final double D = Double.POSITIVE_INFINITY;

    /** The Constant E. */
    static final double E = Double.NaN;

    /**
     * Issue 310.
     */
    @Test
    public void issue310() {
        // Get URL base for overriding classpath (otherwise the JSON representation of the ScanResult won't be
        // the same after the first and second deserialization, because overrideClasspath is set by the first
        // serialization for consistency.)
        final String classfileURL = getClass().getClassLoader()
                .getResource(Issue310.class.getName().replace('.', '/') + ".class").toString();
        final String classpathBase = classfileURL.substring(0,
                classfileURL.length() - (Issue310.class.getName().length() + 6));
        try (ScanResult scanResult1 = new ClassGraph().overrideClasspath(classpathBase)
                .whitelistClasses(Issue310.class.getName()).enableAllInfo().scan()) {
            assertThat(scanResult1.getClassInfo(Issue310.class.getName()).getFieldInfo("B")).isNotNull();
            final String json1 = scanResult1.toJSON(2);
            assertThat(json1).isNotEmpty();
            try (ScanResult scanResult2 = ScanResult.fromJSON(scanResult1.toJSON())) {
                final String json2 = scanResult2.toJSON(2);
                assertThat(json1).isEqualTo(json2);
            }
        }
    }
}
