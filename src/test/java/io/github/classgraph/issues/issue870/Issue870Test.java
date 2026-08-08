package io.github.classgraph.issues.issue870;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;

/**
 * Issue 870 (and issue 643): a glob wildcard in the middle of an accepted
 * package name, e.g. {@code "eu.*.domain"}, matched nothing. Recursive scanning
 * stopped one directory above the wildcard, because the set of accepted-path
 * prefixes -- which is what tells the scanner that a directory may still lead
 * to an accepted path -- was only populated up to the first wildcard.
 */
public class Issue870Test {
    /** The package containing the test fixture packages. */
    private static final String PKG = Issue870Test.class.getPackage().getName();

    private static final String ALPHA_THING = PKG + ".alpha.domain.AlphaThing";
    private static final String BETA_THING = PKG + ".beta.domain.BetaThing";
    private static final String SUB_THING = PKG + ".alpha.domain.sub.SubThing";
    private static final String OTHER_THING = PKG + ".alpha.other.OtherThing";

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
     * A glob in the middle of a package name should match every package it expands
     * to.
     */
    @Test
    public void midPackageGlobMatches() {
        assertThat(scan(PKG + ".*.domain")).contains(ALPHA_THING, BETA_THING);
    }

    /** A mid-package glob should not match packages outside it. */
    @Test
    public void midPackageGlobDoesNotOverMatch() {
        assertThat(scan(PKG + ".*.domain")).doesNotContain(OTHER_THING);
        assertThat(scan(PKG + ".*.nosuchpackage")).isEmpty();
    }

    /**
     * A mid-package glob should work at any depth, including below the wildcard.
     */
    @Test
    public void midPackageGlobMatchesDeeperPackage() {
        assertThat(scan(PKG + ".*.domain.sub")).containsExactly(SUB_THING);
    }

    /**
     * A glob accept is now recursive into sub-packages, just like a literal accept,
     * so {@code *.domain} and the equivalent explicit package list give the same
     * result (#870's original question). This is implemented by letting
     * {@code AcceptRejectPrefix} hold a glob as a regexp prefix pattern, rather
     * than requiring a literal {@code String#startsWith} prefix.
     */
    @Test
    public void midPackageGlobIsRecursiveIntoSubPackages() {
        assertThat(scan(PKG + ".*.domain")).contains(ALPHA_THING, BETA_THING, SUB_THING);
        // The glob and the equivalent explicit package list now agree
        assertThat(scan(PKG + ".*.domain"))
                .containsExactlyInAnyOrderElementsOf(scan(PKG + ".alpha.domain", PKG + ".beta.domain"));
    }

    /**
     * A trailing glob was never affected by this bug (the wildcard is in the last
     * segment, so the prefix set was complete), and is recursive because {@code *}
     * spans package separators.
     */
    @Test
    public void trailingGlobStillMatches() {
        assertThat(scan(PKG + ".*")).contains(ALPHA_THING, BETA_THING, SUB_THING, OTHER_THING);
    }

    /** More than one glob may appear in a single package specifier. */
    @Test
    public void multipleGlobsInOnePackageName() {
        assertThat(scan(PKG + ".*.dom*n")).contains(ALPHA_THING, BETA_THING, SUB_THING);
        assertThat(scan(PKG + ".*.dom*n")).doesNotContain(OTHER_THING);
        assertThat(scan(PKG + ".*.*")).contains(ALPHA_THING, BETA_THING, SUB_THING, OTHER_THING);
    }

    /**
     * A {@code '*'} matches within a single package segment only, so it does not
     * span a package separator. Without this, {@code "*.domain"} would also match
     * {@code alpha.other} via a separator-spanning wildcard.
     */
    @Test
    public void globDoesNotSpanPackageSeparator() {
        // "issue870.*.domain" must not match "issue870.alpha.domain.sub" as a *whole*
        // package name via a
        // separator-spanning wildcard -- it matches it only by recursion below
        // "issue870.alpha.domain"
        assertThat(scan(PKG + ".*.domain.sub")).containsExactly(SUB_THING);
        assertThat(scan(PKG + ".*.sub")).isEmpty();
    }

    /**
     * A trailing {@code "**"} means "and everything below", which is what a
     * recursive accept already does.
     */
    @Test
    public void trailingDoubleGlobIsAcceptedAndMeansRecursive() {
        assertThat(scan(PKG + ".*.domain.**")).containsExactlyInAnyOrderElementsOf(scan(PKG + ".*.domain"));
        assertThat(scan(PKG + ".alpha.domain.**")).contains(ALPHA_THING, SUB_THING);
        assertThat(scan(PKG + ".**")).contains(ALPHA_THING, BETA_THING, SUB_THING, OTHER_THING);
    }

    /**
     * {@code "**"} used as a complete segment matches zero or more whole segments
     * (#940), but {@code "**"} glued to other characters within a segment is
     * rejected.
     */
    @Test
    public void doubleGlobMustFormACompleteSegment() {
        assertThat(scan(PKG + ".**.domain")).contains(ALPHA_THING, BETA_THING, SUB_THING).doesNotContain(OTHER_THING);
        assertThat(scan(PKG + ".**.sub")).containsExactly(SUB_THING);
        assertThatThrownBy(() -> scan(PKG + ".al**ha.domain")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("**");
    }

    /** Reject criteria support globs too, and are likewise recursive. */
    @Test
    public void globRejectIsRecursive() {
        try (var scanResult = new ClassGraph().enableClassInfo().acceptPackages(PKG).rejectPackages(PKG + ".*.domain")
                .scan()) {
            assertThat(scanResult.getAllClasses().getNames()).contains(OTHER_THING).doesNotContain(ALPHA_THING,
                    BETA_THING, SUB_THING);
        }
    }
}
