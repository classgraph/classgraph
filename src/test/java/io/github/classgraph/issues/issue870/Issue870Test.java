package io.github.classgraph.issues.issue870;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

/**
 * Issue 870 (and issue 643): a glob wildcard in the middle of an accepted package name, e.g.
 * {@code "eu.*.domain"}, matched nothing. Recursive scanning stopped one directory above the wildcard, because the
 * set of accepted-path prefixes -- which is what tells the scanner that a directory may still lead to an accepted
 * path -- was only populated up to the first wildcard.
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
     * @param acceptedPackages
     *            the package specifiers to accept
     * @return the names of all classes found
     */
    private static java.util.List<String> scan(final String... acceptedPackages) {
        try (ScanResult scanResult = new ClassGraph().enableClassInfo().acceptPackages(acceptedPackages).scan()) {
            return scanResult.getAllClasses().getNames();
        }
    }

    /** A glob in the middle of a package name should match every package it expands to. */
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

    /** A mid-package glob should work at any depth, including below the wildcard. */
    @Test
    public void midPackageGlobMatchesDeeperPackage() {
        assertThat(scan(PKG + ".*.domain.sub")).containsExactly(SUB_THING);
    }

    /**
     * Known limitation, asserted here so that a change in behaviour is noticed: unlike a literal accepted package,
     * a glob-containing accepted package is <i>not</i> recursive, so it does not match sub-packages of the packages
     * that the glob expands to. {@code ClassGraph#acceptPackages(String...)} only registers a package as a
     * recursive prefix when the package name contains no wildcard, because the prefix matcher cannot hold a glob.
     * So {@code *.domain} finds AlphaThing but not the sub-package's SubThing, whereas the equivalent explicit
     * package list finds both. Making glob accepts recursive requires separating the recursive and non-recursive
     * accept registration paths, which share one code path today.
     */
    @Test
    public void midPackageGlobIsNotRecursiveIntoSubPackages() {
        assertThat(scan(PKG + ".*.domain")).contains(ALPHA_THING, BETA_THING).doesNotContain(SUB_THING);
        // The explicit package list *is* recursive, so the two are not yet equivalent
        assertThat(scan(PKG + ".alpha.domain", PKG + ".beta.domain")).contains(ALPHA_THING, BETA_THING, SUB_THING);
    }

    /**
     * A trailing glob was never affected by this bug (the wildcard is in the last segment, so the prefix set was
     * complete), and is recursive because {@code *} spans package separators.
     */
    @Test
    public void trailingGlobStillMatches() {
        assertThat(scan(PKG + ".*")).contains(ALPHA_THING, BETA_THING, SUB_THING, OTHER_THING);
    }
}
