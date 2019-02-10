package io.github.classgraph.issues.issue310;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

/**
 * The Class Issue310.
 */
public class Issue310 {

    /**
     * The Class DblVal.
     */
    public static class DblVal {
        /** The double val. */
        public double dblVal;

        /**
         * Instantiates a new DblVal.
         *
         * @param dblVal
         *            the DblVal
         */
        public DblVal(final double dblVal) {
            this.dblVal = dblVal;
        }
    }

    static final double a = 3.0;

    static final double b = -4.0;

    static final double c = Double.NEGATIVE_INFINITY;

    static final double d = Double.POSITIVE_INFINITY;

    static final double e = Double.NaN;

    /**
     * Issue 310.
     */
    @Test
    public void issue310() {
        try (ScanResult scanResult1 = new ClassGraph().whitelistClasses(Issue310.class.getName()).enableAllInfo()
                .scan()) {
            assertThat(scanResult1.getClassInfo(Issue310.class.getName()).getFieldInfo("b")).isNotNull();
            final String json1 = scanResult1.toJSON(2);
            assertThat(json1).isNotEmpty();
            try (ScanResult scanResult2 = ScanResult.fromJSON(scanResult1.toJSON())) {
                final String json2 = scanResult2.toJSON(2);
                assertThat(json1).isEqualTo(json2);
            }
        }
    }
}
