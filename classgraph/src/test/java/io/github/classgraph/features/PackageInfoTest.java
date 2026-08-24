package io.github.classgraph.features;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Objects;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.PackageInfo;
import io.github.classgraph.ScanResult;
import io.github.classgraph.features.packages.PackageMember;
import io.github.classgraph.features.packages.classless.leaf.LeafMember;

/**
 * Tests the {@link PackageInfo} objects built by a scan, which form a tree mirroring the package hierarchy of the
 * scanned classes.
 */
public class PackageInfoTest {
    /** The root of the scanned package tree, which holds {@link PackageMember}. */
    private static final String ROOT = PackageMember.class.getPackageName();

    /** A package between {@link #ROOT} and {@link #LEAF}, which holds no classes of its own. */
    private static final String MIDDLE = ROOT + ".classless";

    /** The deepest scanned package, which holds {@link LeafMember}. */
    private static final String LEAF = LeafMember.class.getPackageName();

    /** The scan the packages under test come from. */
    private static ScanResult scanResult;

    /** A second scan of the same classes, to compare packages that did not come from the same scan. */
    private static ScanResult secondScanResult;

    /** Scan the fixture packages twice. */
    @BeforeAll
    static void scanFixturePackages() {
        scanResult = new ClassGraph().enableClasspath().acceptPackages(ROOT).scan();
        secondScanResult = new ClassGraph().enableClasspath().acceptPackages(ROOT).scan();
    }

    /** Close both scans. */
    @AfterAll
    static void closeScans() {
        scanResult.close();
        secondScanResult.close();
    }

    /**
     * Get the {@link PackageInfo} for a package that was scanned.
     *
     * @param packageName
     *            the package name.
     * @return the {@link PackageInfo}.
     */
    private static PackageInfo packageInfo(final String packageName) {
        return Objects.requireNonNull(scanResult.getPackageInfo(packageName),
                () -> "No PackageInfo for " + packageName);
    }

    // -----------------------------------------------------------------------------------------------------------

    /** Only the packages reached by the scan have a {@link PackageInfo}. */
    @Test
    public void onlyScannedPackagesAreFound() {
        assertThat(scanResult.getPackageInfo().getNames()).containsExactly(ROOT, MIDDLE, LEAF);
        assertThat(scanResult.getPackageInfo("com.nonexistent.package")).isNull();
    }

    /** A package holds the classes declared in it, and can be asked for one of them by name. */
    @Test
    public void aPackageHoldsTheClassesDeclaredInIt() {
        final var root = packageInfo(ROOT);
        assertThat(root.getClassInfo(PackageMember.class.getName()))
                .isSameAs(scanResult.getClassInfo(PackageMember.class.getName()));
        assertThat(root.getClassInfo().getNames()).containsExactly(PackageMember.class.getName());

        // A class of a subpackage is not a member of an enclosing package
        assertThat(root.getClassInfo(LeafMember.class.getName())).isNull();
        assertThat(root.getClassInfo("com.nonexistent.NoSuchClass")).isNull();
    }

    /** A package that holds no classes of its own is still part of the tree. */
    @Test
    public void aPackageThatHoldsNoClassesIsStillPartOfTheTree() {
        final var middle = packageInfo(MIDDLE);
        assertThat(middle.getClassInfo()).isEmpty();
        assertThat(middle.getClassInfo(LeafMember.class.getName())).isNull();
        assertThat(middle.getChildren()).containsExactly(packageInfo(LEAF));
    }

    /** Asked recursively, a package also holds the classes of its subpackages. */
    @Test
    public void aPackageRecursivelyHoldsTheClassesOfItsSubpackages() {
        assertThat(packageInfo(ROOT).getClassInfoRecursive().getNames())
                .containsExactly(PackageMember.class.getName(), LeafMember.class.getName());
        assertThat(packageInfo(LEAF).getClassInfoRecursive().getNames())
                .containsExactly(LeafMember.class.getName());
    }

    /** A class knows which package it is in. */
    @Test
    public void aClassKnowsItsPackage() {
        assertThat(Objects.requireNonNull(scanResult.getClassInfo(LeafMember.class.getName())).getPackageInfo())
                .isEqualTo(packageInfo(LEAF));
    }

    /** Packages are linked to their parent and their children, and the outermost scanned package has no parent. */
    @Test
    public void packagesFormATree() {
        final var root = packageInfo(ROOT);
        final var middle = packageInfo(MIDDLE);
        final var leaf = packageInfo(LEAF);

        assertThat(root.getParent()).isNull();
        assertThat(root.getChildren()).containsExactly(middle);
        assertThat(middle.getParent()).isEqualTo(root);
        assertThat(leaf.getParent()).isEqualTo(middle);
        assertThat(leaf.getChildren()).isEmpty();
    }

    /** Packages are named, ordered and rendered by their package name. */
    @Test
    public void packagesAreNamedAndOrderedByPackageName() {
        final var root = packageInfo(ROOT);
        final var leaf = packageInfo(LEAF);

        assertThat(root.getName()).isEqualTo(ROOT);
        assertThat(root.toString()).isEqualTo("package " + ROOT);
        assertThat(root).isLessThan(leaf);
        assertThat(leaf).isGreaterThan(root);
        assertThat(scanResult.getPackageInfo().getNames()).isSorted();
    }

    /** Two packages are the same package if they have the same name, whichever scan they came from. */
    @Test
    public void packagesWithTheSameNameAreEqual() {
        final var root = packageInfo(ROOT);
        final var rootFromSecondScan = secondScanResult.getPackageInfo(ROOT);

        assertThat(root).isEqualTo(root).isEqualTo(rootFromSecondScan).hasSameHashCodeAs(rootFromSecondScan)
                .isNotSameAs(rootFromSecondScan);
        assertThat(root).isNotEqualTo(packageInfo(LEAF)).isNotEqualTo(ROOT).isNotEqualTo(null);
    }
}
