package io.github.classgraph.features.externalpackage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.features.externalpackage.accepted.AcceptedSubclass;
import io.github.classgraph.features.externalpackage.external.ExternalSuperclass;

/**
 * Test that a class that was only scanned because an accepted class refers to it is not listed as a member of its
 * package or module, so that {@link io.github.classgraph.PackageInfo} agrees with
 * {@link io.github.classgraph.ScanResult#getAllClasses()}.
 */
public class ExternalClassPackageMembershipTest {
    private static final String ACCEPTED_PACKAGE = AcceptedSubclass.class.getPackage().getName();

    private static final String EXTERNAL_PACKAGE = ExternalSuperclass.class.getPackage().getName();

    /** Only the accepted package is scanned; its classes' external superclass is read but not listed. */
    @Test
    public void externalClassIsNotListedAsAPackageMember() {
        try (var scanResult = new ClassGraph().acceptPackages(ACCEPTED_PACKAGE).scan()) {
            // The external superclass was read -- it is reachable as a superclass
            assertThat(scanResult.getClassInfo(AcceptedSubclass.class.getName()).getSuperclass().getName())
                    .isEqualTo(ExternalSuperclass.class.getName());

            // ... but getAllClasses() leaves it out, and so does its package
            assertThat(scanResult.getAllClasses().getNames()).containsExactly(AcceptedSubclass.class.getName());
            assertThat(scanResult.getPackageInfo().getNames()).doesNotContain(EXTERNAL_PACKAGE);
            assertThat(scanResult.getPackageInfo(EXTERNAL_PACKAGE)).isNull();
            assertThat(scanResult.getPackageInfo(ACCEPTED_PACKAGE).getClassInfo().getNames())
                    .containsExactly(AcceptedSubclass.class.getName());
            assertThat(scanResult.getClassInfo(ExternalSuperclass.class.getName()).getPackageInfo()).isNull();
        }
    }

    /** With external classes enabled, the external superclass is a member of its package like any other class. */
    @Test
    public void externalClassIsListedAsAPackageMemberIfEnabled() {
        try (var scanResult = new ClassGraph().acceptPackages(ACCEPTED_PACKAGE).enableExternalClasses().scan()) {
            assertThat(scanResult.getAllClasses().getNames()).contains(ExternalSuperclass.class.getName());
            assertThat(scanResult.getPackageInfo(EXTERNAL_PACKAGE).getClassInfo().getNames())
                    .containsExactly(ExternalSuperclass.class.getName());
            assertThat(scanResult.getClassInfo(ExternalSuperclass.class.getName()).getPackageInfo().getName())
                    .isEqualTo(EXTERNAL_PACKAGE);
        }
    }

    /**
     * The same holds for modules: {@code java.util.ArrayList} is accepted, so the module it is in is scanned, but
     * its superclass {@code java.util.AbstractList} is read as an external class and is not listed as a member of
     * that module.
     */
    @Test
    public void externalClassIsNotListedAsAModuleMember() {
        try (var scanResult = new ClassGraph().acceptClasses("java.util.ArrayList").enableSystemJarsAndModules()
                .scan()) {
            assertThat(scanResult.getClassInfo("java.util.AbstractList")).isNotNull();
            assertThat(scanResult.getModuleInfo("java.base").getClassInfo().getNames())
                    .containsExactly("java.util.ArrayList");
            assertThat(scanResult.getClassInfo("java.util.AbstractList").getModuleInfo()).isNull();
        }
    }
}
