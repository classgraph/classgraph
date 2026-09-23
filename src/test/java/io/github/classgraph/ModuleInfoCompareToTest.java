package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;

import io.github.classgraph.Scanner.ClasspathEntryWorkUnit;
import nonapi.io.github.classgraph.classloaderhandler.ClassLoaderHandlerRegistry;
import nonapi.io.github.classgraph.scanspec.ScanSpec;

/**
 * A module with no known location used to compare equal to every module of the same name, so the ordering was not
 * transitive: with locations {@code null}, {@code a} and {@code b}, the first compared equal to the other two, which
 * did not compare equal to each other.
 */
public class ModuleInfoCompareToTest {
    /**
     * Construct a {@link ModuleInfo} for a module named {@code com.xyz.located}, with the given location.
     *
     * @param location
     *            the module location, or null for none.
     * @return the {@link ModuleInfo}.
     */
    private static ModuleInfo moduleWithLocation(final URI location) {
        final ClasspathEntryWorkUnit workUnit = new ClasspathEntryWorkUnit(Paths.get("."),
                /* classLoader = */ null, /* parentClasspathElement = */ null, /* classpathElementIdx = */ 0,
                /* packageRootPrefix = */ "", ClassLoaderHandlerRegistry.NO_PACKAGE_ROOT_PREFIXES);
        // The classpath element is never opened, so it is only asked for its module name and location
        final ClasspathElement classpathElement = new ClasspathElementDir(workUnit, /* nestedJarHandler = */ null,
                new ScanSpec()) {
            @Override
            public String getModuleName() {
                return "com.xyz.located";
            }

            @Override
            URI getURI() {
                if (location == null) {
                    throw new IllegalArgumentException("No location");
                }
                return location;
            }
        };
        return new ModuleInfo(/* moduleRef = */ null, classpathElement);
    }

    /** A module with no known location sorts before a module of the same name that has one. */
    @Test
    public void aModuleWithNoLocationSortsBeforeOneWithALocation() {
        final ModuleInfo noLocation = moduleWithLocation(null);
        final ModuleInfo locationA = moduleWithLocation(URI.create("file:/a"));
        final ModuleInfo locationB = moduleWithLocation(URI.create("file:/b"));
        assertThat(noLocation.getLocation()).isNull();

        assertThat(noLocation.compareTo(locationA)).isNegative();
        assertThat(locationA.compareTo(noLocation)).isPositive();
        assertThat(noLocation.compareTo(locationB)).isNegative();
        assertThat(locationA.compareTo(locationB)).isNegative();
        assertThat(noLocation.compareTo(moduleWithLocation(null))).isZero();
        assertThat(noLocation).isNotEqualTo(locationA).isEqualTo(moduleWithLocation(null));
    }
}
