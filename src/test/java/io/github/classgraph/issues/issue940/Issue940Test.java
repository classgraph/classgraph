package io.github.classgraph.issues.issue940;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;
import nonapi.io.github.classgraph.scanspec.AcceptReject;

/**
 * Issue 940: after 4.8.186, a single {@code '*'} no longer spans package separators, and there was no way to match
 * an unknown number of intermediate packages mid-pattern. {@code "**"} is now a multi-segment wildcard in any
 * position (not only trailing).
 */
public class Issue940Test {
    private static final String PKG = Issue940Test.class.getPackage().getName();

    private static final String SCHEMA_CLASS = PKG + ".api.base.schema.SchemaClass";
    private static final String OTHER_SCHEMA_CLASS = PKG + ".other.schema.OtherSchemaClass";
    private static final String MODULE_CLASS = PKG + ".api.base.test.module.ModuleClass";
    private static final String OTHER_CLASS = PKG + ".api.base.other.OtherClass";

    private static List<String> scan(final String... acceptedPackages) {
        try (ScanResult scanResult = new ClassGraph().enableClassInfo().acceptPackages(acceptedPackages).scan()) {
            return scanResult.getAllClasses().getNames();
        }
    }

    /**
     * The motivating example from the issue: {@code org.creekservice.**.schema} style patterns must match packages
     * with an unknown number of intermediate segments.
     */
    @Test
    public void midPackageDoubleGlobMatchesUnknownIntermediatePackages() {
        assertThat(scan(PKG + ".**.schema")).contains(SCHEMA_CLASS, OTHER_SCHEMA_CLASS);
        assertThat(scan(PKG + ".**.schema")).doesNotContain(MODULE_CLASS, OTHER_CLASS);
    }

    /** A single {@code '*'} still matches only one segment (the 4.8.186 behaviour). */
    @Test
    public void singleStarStillDoesNotSpanSegments() {
        assertThat(scan(PKG + ".*.schema")).contains(OTHER_SCHEMA_CLASS);
        assertThat(scan(PKG + ".*.schema")).doesNotContain(SCHEMA_CLASS);
    }

    /**
     * Leading multi-segment wildcard: {@code **.api.base.test.module} recovers the pre-4.8.186 ability of
     * {@code *.api.*}-style patterns to match when {@code api} is not the first package segment.
     */
    @Test
    public void leadingDoubleGlobMatchesBeforeKnownSegment() {
        assertThat(scan("**.api.base.test.module")).contains(MODULE_CLASS);
        assertThat(scan(PKG + ".api.**")).contains(SCHEMA_CLASS, MODULE_CLASS, OTHER_CLASS);
    }

    /** Mid-pattern {@code "**"} is recursive into sub-packages of a matched package, like a literal accept. */
    @Test
    public void midPackageDoubleGlobIsRecursive() {
        // Matching the package "….api.base" via ** should also include its sub-packages when accepted recursively
        assertThat(scan(PKG + ".**.base")).contains(SCHEMA_CLASS, MODULE_CLASS, OTHER_CLASS);
        assertThat(scan(PKG + ".**.base")).doesNotContain(OTHER_SCHEMA_CLASS);
    }

    /** Reject criteria support mid-pattern {@code "**"} too. */
    @Test
    public void midPackageDoubleGlobReject() {
        try (ScanResult scanResult = new ClassGraph().enableClassInfo().acceptPackages(PKG)
                .rejectPackages(PKG + ".**.schema").scan()) {
            assertThat(scanResult.getAllClasses().getNames()).contains(MODULE_CLASS, OTHER_CLASS)
                    .doesNotContain(SCHEMA_CLASS, OTHER_SCHEMA_CLASS);
        }
    }

    /** Direct unit coverage of the glob-to-pattern conversion used by package accept/reject. */
    @Test
    public void segmentGlobToPatternSupportsMidDoubleGlob() {
        final Pattern packagePattern = AcceptReject.segmentGlobToPattern("org.creekservice.**.schema", '.',
                /* prefixMatch = */ false);
        assertThat(packagePattern.matcher("org.creekservice.api.base.schema").matches()).isTrue();
        assertThat(packagePattern.matcher("org.creekservice.other.schema").matches()).isTrue();
        assertThat(packagePattern.matcher("org.creekservice.schema").matches()).isTrue();
        assertThat(packagePattern.matcher("org.creekservice.api.base.other").matches()).isFalse();

        final Pattern pathPattern = AcceptReject.segmentGlobToPattern("org/creekservice/**/schema/", '/',
                /* prefixMatch = */ false);
        assertThat(pathPattern.matcher("org/creekservice/api/base/schema/").matches()).isTrue();
        assertThat(pathPattern.matcher("org/creekservice/other/schema/").matches()).isTrue();
        assertThat(pathPattern.matcher("org/creekservice/schema/").matches()).isTrue();

        // Single '*' remains segment-bounded
        final Pattern singleStar = AcceptReject.segmentGlobToPattern("org.creekservice.*.schema", '.', false);
        assertThat(singleStar.matcher("org.creekservice.other.schema").matches()).isTrue();
        assertThat(singleStar.matcher("org.creekservice.api.base.schema").matches()).isFalse();
    }
}
