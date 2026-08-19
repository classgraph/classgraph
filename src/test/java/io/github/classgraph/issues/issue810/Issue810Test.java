package io.github.classgraph.issues.issue810;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

/**
 * The first part of {@code io.github.classgraph.issues.issue673.Issue673Test} failed intermittently, because the
 * classpath element for {@code b.zip} could be created either by the work unit for the toplevel classpath entry
 * {@code b.zip}, or by the work unit for the {@code Class-Path} manifest entry of {@code a.zip} that also points at
 * {@code b.zip}, whichever won the race to the classpath element singleton map. Only the winner's position in the
 * classpath order was recorded, so if the manifest entry won, {@code b.zip} was ordered as a child of {@code a.zip}
 * rather than as the first toplevel classpath element.
 *
 * <p>
 * The race is timing-dependent (it was reported on Windows and Mac OS X), and cannot be forced, so repeating the
 * scan here does not reliably reproduce the wrong order -- this is an end-to-end smoke test of the classpath order.
 * The precedence rule that the fix depends on is tested directly, and deterministically, in
 * {@code io.github.classgraph.ClasspathElementReferenceTest}.
 */
class Issue810Test {
    /** a has a Class-Path manifest entry that points to b, and b points to c. */
    @Test
    void classpathOrderIsDeterministic() {
        final URL aURL = Issue810Test.class.getClassLoader().getResource("issue673/a.zip");
        final URL bURL = Issue810Test.class.getClassLoader().getResource("issue673/b.zip");

        // A toplevel classpath entry must take precedence over a Class-Path manifest entry that references the
        // same jar, so b.zip must be ordered first, even though a.zip's manifest also references it
        for (int i = 0; i < 20; i++) {
            try (ScanResult scanResult = new ClassGraph().overrideClasspath(bURL, aURL).scan()) {
                final List<String> order = scanResult.getClasspathFiles().stream().map(f -> f.getName())
                        .collect(Collectors.toList());
                assertThat(order).isEqualTo(Arrays.asList("b.zip", "c.zip", "a.zip"));
            }
            try (ScanResult scanResult = new ClassGraph().overrideClasspath(aURL, bURL).scan()) {
                final List<String> order = scanResult.getClasspathFiles().stream().map(f -> f.getName())
                        .collect(Collectors.toList());
                assertThat(order).isEqualTo(Arrays.asList("a.zip", "b.zip", "c.zip"));
            }
        }
    }
}
