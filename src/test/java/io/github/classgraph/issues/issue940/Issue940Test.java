package io.github.classgraph.issues.issue940;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import nonapi.io.github.classgraph.scanspec.AcceptReject;

/**
 * Issue 940: since 4.8.186, {@code '*'} in an accepted package name matches
 * within a single package segment only, which left no way to match an unknown
 * number of intermediate package segments (before 4.8.186, e.g.
 * {@code "org.creekservice.*.schema"} matched
 * {@code org.creekservice.api.base.schema}). {@code "**"}, used as a complete
 * segment, now matches zero or more package segments.
 */
public class Issue940Test {
    /** The package containing the test fixture packages. */
    private static final String PKG = Issue940Test.class.getPackage().getName();

    private static final String API_BASE_SCHEMA_THING = PKG + ".api.base.schema.ApiBaseSchemaThing";
    private static final String OTHER_SCHEMA_THING = PKG + ".other.schema.OtherSchemaThing";
    private static final String TOP_LEVEL_SCHEMA_THING = PKG + ".schema.TopLevelSchemaThing";
    private static final String NON_SCHEMA_THING = PKG + ".api.base.other.NonSchemaThing";

    /**
     * Scan with the given accepted package specifiers.
     *
     * @param acceptedPackages the package specifiers to accept
     * @return the names of all classes found
     */
    private static List<String> scan(final String... acceptedPackages) {
        try (var scanResult = new ClassGraph().enableClassInfo().acceptPackages(acceptedPackages).scan()) {
            return scanResult.getAllClasses().getNames();
        }
    }

    /**
     * {@code "**"} in the middle of a package name matches zero or more package
     * segments.
     */
    @Test
    public void midPackageDoubleGlobSpansSegments() {
        assertThat(scan(PKG + ".**.schema")).contains(API_BASE_SCHEMA_THING, OTHER_SCHEMA_THING, TOP_LEVEL_SCHEMA_THING)
                .doesNotContain(NON_SCHEMA_THING);
    }

    /**
     * {@code "**"} matches <i>zero</i> or more segments (just as {@code '*'}
     * matches zero or more characters), while {@code '*'} still matches exactly one
     * whole segment.
     */
    @Test
    public void doubleGlobMatchesZeroSegments() {
        assertThat(scan(PKG + ".**.schema")).contains(TOP_LEVEL_SCHEMA_THING);
        assertThat(scan(PKG + ".*.schema")).contains(OTHER_SCHEMA_THING) //
                .doesNotContain(API_BASE_SCHEMA_THING, TOP_LEVEL_SCHEMA_THING);
    }

    /**
     * A leading {@code "**"} matches zero or more package segments at the start of
     * a package name.
     */
    @Test
    public void leadingDoubleGlobSpansSegments() {
        assertThat(scan("**.issue940.api")).contains(API_BASE_SCHEMA_THING, NON_SCHEMA_THING)
                .doesNotContain(OTHER_SCHEMA_THING, TOP_LEVEL_SCHEMA_THING);
    }

    /**
     * A {@code "**"} glob accept is recursive into sub-packages, like any other
     * accept.
     */
    @Test
    public void doubleGlobAcceptIsRecursiveIntoSubPackages() {
        assertThat(scan(PKG + ".**.base")).contains(API_BASE_SCHEMA_THING, NON_SCHEMA_THING)
                .doesNotContain(OTHER_SCHEMA_THING, TOP_LEVEL_SCHEMA_THING);
    }

    /**
     * {@code "**"} works in reject criteria too, and rejects recursively (including
     * the zero-segment case).
     */
    @Test
    public void doubleGlobRejectSpansSegments() {
        try (var scanResult = new ClassGraph().enableClassInfo().acceptPackages(PKG).rejectPackages(PKG + ".**.schema")
                .scan()) {
            assertThat(scanResult.getAllClasses().getNames()).contains(NON_SCHEMA_THING)
                    .doesNotContain(API_BASE_SCHEMA_THING, OTHER_SCHEMA_THING, TOP_LEVEL_SCHEMA_THING);
        }
    }

    /**
     * Pattern-level check of the {@code "**"} glob translation, for both package
     * and path separators.
     */
    @Test
    public void segmentGlobToPatternHandlesDoubleGlob() {
        final var packagePattern = AcceptReject.segmentGlobToPattern("org.creekservice.**.schema", '.',
                /* prefixMatch = */ false);
        assertThat(packagePattern.matcher("org.creekservice.api.base.schema").matches()).isTrue();
        assertThat(packagePattern.matcher("org.creekservice.other.schema").matches()).isTrue();
        assertThat(packagePattern.matcher("org.creekservice.schema").matches()).isTrue();
        assertThat(packagePattern.matcher("org.creekservice.api.base.schemaX").matches()).isFalse();
        assertThat(packagePattern.matcher("org.creekservice.api.base.schema.sub").matches()).isFalse();
        assertThat(packagePattern.matcher("org.creekserviceschema").matches()).isFalse();

        final var leadingPattern = AcceptReject.segmentGlobToPattern("**.api.*", '.', /* prefixMatch = */ false);
        assertThat(leadingPattern.matcher("org.creekservice.api.base").matches()).isTrue();
        assertThat(leadingPattern.matcher("api.base").matches()).isTrue();
        assertThat(leadingPattern.matcher("xapi.base").matches()).isFalse();

        final var pathPattern = AcceptReject.segmentGlobToPattern("org/creekservice/**/schema/", '/',
                /* prefixMatch = */ false);
        assertThat(pathPattern.matcher("org/creekservice/api/base/schema/").matches()).isTrue();
        assertThat(pathPattern.matcher("org/creekservice/schema/").matches()).isTrue();
        assertThat(pathPattern.matcher("org/creekservice/api/other/").matches()).isFalse();
    }

    /**
     * {@code "**"} spans path segments in {@link ClassGraph#acceptPaths}, not just
     * in accepted package names.
     */
    @Test
    public void midPathDoubleGlobSpansSegments() {
        final var pkgPath = PKG.replace('.', '/');
        try (var scanResult = new ClassGraph().acceptPaths(pkgPath + "/**/schema").scan()) {
            assertThat(scanResult.getAllResources().getPaths())
                    .contains(pkgPath + "/api/base/schema/ApiBaseSchemaThing.class",
                            pkgPath + "/other/schema/OtherSchemaThing.class",
                            pkgPath + "/schema/TopLevelSchemaThing.class")
                    .doesNotContain(pkgPath + "/api/base/other/NonSchemaThing.class");
        }
    }

    /** {@code "**"} that does not form a complete segment is still rejected. */
    @Test
    public void gluedDoubleGlobIsRejected() {
        assertThatThrownBy(() -> AcceptReject.segmentGlobToPattern("com.a**b.impl", '.', /* prefixMatch = */ false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("**");
        assertThatThrownBy(() -> AcceptReject.segmentGlobToPattern("com.***.impl", '.', /* prefixMatch = */ false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("**");
    }
}
