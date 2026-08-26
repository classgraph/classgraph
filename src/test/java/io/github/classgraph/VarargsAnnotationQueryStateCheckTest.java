package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

/**
 * {@link ScanResult#getClassesWithAllAnnotations(String...)} and
 * {@link ScanResult#getClassesWithAnyAnnotation(String...)} only reached their state check through the per-name
 * query inside the loop, so calling either with no annotation names returned the empty list rather than throwing,
 * even on a closed scan result or one that was scanned without annotation info.
 */
public class VarargsAnnotationQueryStateCheckTest {
    /** An empty query on a scan result that was scanned without annotation info throws. */
    @Test
    public void emptyQueryThrowsIfAnnotationInfoWasNotEnabled() {
        try (ScanResult scanResult = new ClassGraph()
                .acceptPackages(VarargsAnnotationQueryStateCheckTest.class.getPackage().getName())
                .enableClassInfo().scan()) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> scanResult.getClassesWithAllAnnotations(new String[0]))
                    .withMessageContaining("enableAnnotationInfo");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> scanResult.getClassesWithAnyAnnotation(new String[0]))
                    .withMessageContaining("enableAnnotationInfo");
        }
    }

    /** An empty query on a closed scan result throws. */
    @Test
    public void emptyQueryThrowsIfScanResultIsClosed() {
        final ScanResult scanResult = new ClassGraph()
                .acceptPackages(VarargsAnnotationQueryStateCheckTest.class.getPackage().getName()).enableAllInfo()
                .scan();
        scanResult.close();
        assertThatIllegalArgumentException().isThrownBy(() -> scanResult.getClassesWithAllAnnotations(new String[0]))
                .withMessageContaining("after it has been closed");
        assertThatIllegalArgumentException().isThrownBy(() -> scanResult.getClassesWithAnyAnnotation(new String[0]))
                .withMessageContaining("after it has been closed");
    }
}
