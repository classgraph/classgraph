package io.github.classgraph.classpath;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Tests for {@link TransitiveClasspath}. */
class TransitiveClasspathTest {
    /** A classpath element whose path was not changed by canonicalization declares its children unchanged. */
    @Test
    void aChildOfAnUncanonicalizedPathIsLeftAlone() {
        assertThat(TransitiveClasspath.spelledAsReached("/dir/lib/other.jar", "/dir/lib.jar", "/dir/lib.jar"))
                .isEqualTo("/dir/lib/other.jar");
    }

    /** A path within the jarfile is spelled with the path the jarfile was reached at. */
    @Test
    void aPathWithinTheJarfileIsRespelled() {
        assertThat(TransitiveClasspath.spelledAsReached("/dir/lib.jar!/BOOT-INF/lib/dep.jar", "/dir/lib.jar",
                "/link/app.jar")).isEqualTo("/link/app.jar!/BOOT-INF/lib/dep.jar");
    }

    /** A path within a directory classpath element is spelled with the path the directory was reached at. */
    @Test
    void aPathWithinTheDirectoryIsRespelled() {
        assertThat(
                TransitiveClasspath.spelledAsReached("/dir/classes/lib/dep.jar", "/dir/classes", "/link/classes"))
                .isEqualTo("/link/classes/lib/dep.jar");
    }

    /** A path beside the jarfile is spelled with the directory the jarfile was reached in. */
    @Test
    void aPathBesideTheJarfileIsRespelled() {
        assertThat(TransitiveClasspath.spelledAsReached("/dir/dep.jar", "/dir/lib.jar", "/link/app.jar"))
                .isEqualTo("/link/dep.jar");
    }

    /**
     * A file whose name merely starts with the name of the jarfile is beside the jarfile, not within it, so it is
     * spelled with the directory the jarfile was reached in rather than with the path of the jarfile itself.
     */
    @Test
    void aPathThatOnlyStartsWithTheNameOfTheJarfileIsNotTreatedAsBeingWithinIt() {
        assertThat(TransitiveClasspath.spelledAsReached("/dir/lib.jar-extra.jar", "/dir/lib.jar", "/link/app.jar"))
                .isEqualTo("/link/lib.jar-extra.jar");
    }

    /** A directory whose name merely starts with the name of a directory classpath element is not within it. */
    @Test
    void aPathThatOnlyStartsWithTheNameOfTheDirectoryIsNotTreatedAsBeingWithinIt() {
        assertThat(
                TransitiveClasspath.spelledAsReached("/dir/classes-old/dep.jar", "/dir/classes", "/link/classes"))
                .isEqualTo("/link/classes-old/dep.jar");
    }

    /** A path that is neither within the classpath element nor beside it is left alone. */
    @Test
    void anAbsolutePathElsewhereIsLeftAlone() {
        assertThat(TransitiveClasspath.spelledAsReached("/elsewhere/dep.jar", "/dir/lib.jar", "/link/app.jar"))
                .isEqualTo("/elsewhere/dep.jar");
    }

    /** Only the outermost path component is canonicalized, so a path within a nested jarfile is respelled. */
    @Test
    void aPathWithinANestedJarfileIsRespelled() {
        assertThat(TransitiveClasspath.spelledAsReached("/dir/lib.jar!/BOOT-INF/classes/dep.jar",
                "/dir/lib.jar!/BOOT-INF/classes", "/link/app.jar!/BOOT-INF/classes"))
                .isEqualTo("/link/app.jar!/BOOT-INF/classes/dep.jar");
    }
}
