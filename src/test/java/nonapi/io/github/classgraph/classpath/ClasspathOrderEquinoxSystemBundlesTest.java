package nonapi.io.github.classgraph.classpath;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import nonapi.io.github.classgraph.reflection.ReflectionUtils;
import nonapi.io.github.classgraph.scanspec.ScanSpec;

/**
 * Tests that the "Equinox system bundles have been added" flag is scoped to a
 * single scan, rather than being held in a static field that stays set for the
 * lifetime of the JVM.
 */
public class ClasspathOrderEquinoxSystemBundlesTest {
    /**
     * Create a {@link ClasspathOrder}, as {@code ClasspathFinder} does once per
     * scan.
     *
     * @return a new {@link ClasspathOrder}.
     */
    private static ClasspathOrder newClasspathOrder() {
        return new ClasspathOrder(new ScanSpec(), new ReflectionUtils());
    }

    /**
     * Within one scan, the system bundles are added by the first Equinox
     * classloader only.
     */
    @Test
    public void systemBundlesAreAddedOnlyOncePerScan() {
        final var classpathOrder = newClasspathOrder();
        assertThat(classpathOrder.tryAddEquinoxSystemBundles()).isTrue();
        assertThat(classpathOrder.tryAddEquinoxSystemBundles()).isFalse();
        assertThat(classpathOrder.tryAddEquinoxSystemBundles()).isFalse();
    }

    /**
     * Every scan gets a fresh {@link ClasspathOrder}, so the system bundles are
     * added again by each scan. If this flag were static, every scan after the
     * first would silently omit the Equinox system bundles.
     */
    @Test
    public void systemBundlesAreAddedAgainByTheNextScan() {
        assertThat(newClasspathOrder().tryAddEquinoxSystemBundles()).isTrue();
        assertThat(newClasspathOrder().tryAddEquinoxSystemBundles()).isTrue();
        assertThat(newClasspathOrder().tryAddEquinoxSystemBundles()).isTrue();
    }
}
