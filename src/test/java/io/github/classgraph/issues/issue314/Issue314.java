package io.github.classgraph.issues.issue314;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

/**
 * The Class Issue314.
 */
public class Issue314 {

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
        try (ScanResult scanResult1 = new ClassGraph().whitelistPackages(Issue314.class.getPackage().getName())
                .enableAllInfo().scan()) {
            assertThat(scanResult1.getClassInfo(A.class.getName())).isNotNull();
            assertThat(scanResult1.getClassInfo(B.class.getName())).isNotNull();
            final String json1 = scanResult1.toJSON(2);
            System.out.println(json1);
            assertThat(json1).isNotEmpty();
            try (final ScanResult scanResult2 = ScanResult.fromJSON(scanResult1.toJSON())) {
                final String json2 = scanResult2.toJSON(2);
                System.out.println("\n" + json2);

                assertThat(json1).isEqualTo(json2);
                assertThat(scanResult1.getSubclasses(A.class.getName()).getNames()).containsOnly(B.class.getName());
                assertThat(scanResult2.getSubclasses(A.class.getName()).getNames()).containsOnly(B.class.getName());
            }
        }
    }
}
