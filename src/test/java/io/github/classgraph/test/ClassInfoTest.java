package io.github.classgraph.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ClassInfoList.ClassInfoFilter;
import io.github.classgraph.ScanResult;
import io.github.classgraph.test.whitelisted.ClsSub;
import io.github.classgraph.test.whitelisted.ClsSubSub;
import io.github.classgraph.test.whitelisted.Iface;
import io.github.classgraph.test.whitelisted.IfaceSub;
import io.github.classgraph.test.whitelisted.IfaceSubSub;
import io.github.classgraph.test.whitelisted.Impl1;
import io.github.classgraph.test.whitelisted.Impl1Sub;
import io.github.classgraph.test.whitelisted.Impl1SubSub;
import io.github.classgraph.test.whitelisted.Impl2;
import io.github.classgraph.test.whitelisted.Impl2Sub;
import io.github.classgraph.test.whitelisted.Impl2SubSub;

/**
 * ClassInfoTest.
 */
public class ClassInfoTest {
    /** The scan result. */
    private static ScanResult scanResult;

    /**
     * Setup.
     */
    @BeforeAll
    public static void setup() {
        scanResult = new ClassGraph().whitelistPackages(Impl1.class.getPackage().getName()).scan();
    }

    /**
     * Teardown.
     */
    @AfterAll
    public static void teardown() {
        scanResult.close();
        scanResult = null;
    }

    /**
     * Use class name to class info.
     */
    @Test
    public void useClassNameToClassInfo() {
        final List<String> impls = scanResult.getClassInfo(Iface.class.getName()).getClassesImplementing()
                .getNames();
        assertThat(impls.contains(Impl1.class.getName())).isTrue();
    }

    /**
     * Filter.
     */
    @Test
    public void filter() {
        assertThat(scanResult.getAllClasses().filter(new ClassInfoFilter() {
            @Override
            public boolean accept(final ClassInfo ci) {
                return ci.getName().contains("ClsSub");
            }
        }).getNames()).containsOnly(ClsSub.class.getName(), ClsSubSub.class.getName());
    }

    /**
     * Stream has super interface direct.
     */
    @Test
    public void streamHasSuperInterfaceDirect() {
        assertThat(scanResult.getAllClasses().filter(new ClassInfoFilter() {
            @Override
            public boolean accept(final ClassInfo ci) {
                return ci.getInterfaces().directOnly().getNames().contains(Iface.class.getName());
            }
        }).getNames()).containsOnly(IfaceSub.class.getName(), Impl2.class.getName());
    }

    /**
     * Stream has super interface.
     */
    @Test
    public void streamHasSuperInterface() {
        assertThat(scanResult.getAllClasses().filter(new ClassInfoFilter() {
            @Override
            public boolean accept(final ClassInfo ci) {
                return ci.getInterfaces().getNames().contains(Iface.class.getName());
            }
        }).getNames()).containsOnly(IfaceSub.class.getName(), IfaceSubSub.class.getName(), Impl2.class.getName(),
                Impl2Sub.class.getName(), Impl2SubSub.class.getName(), Impl1.class.getName(),
                Impl1Sub.class.getName(), Impl1SubSub.class.getName());
    }

    /**
     * Implements interface direct.
     */
    @Test
    public void implementsInterfaceDirect() {
        assertThat(scanResult.getClassesImplementing(Iface.class.getName()).directOnly().getNames())
                .containsOnly(IfaceSub.class.getName(), Impl2.class.getName());
    }

    /**
     * Implements interface.
     */
    @Test
    public void implementsInterface() {
        assertThat(scanResult.getClassesImplementing(Iface.class.getName()).getNames()).containsOnly(
                Impl1.class.getName(), Impl1Sub.class.getName(), Impl1SubSub.class.getName(), Impl2.class.getName(),
                Impl2Sub.class.getName(), Impl2SubSub.class.getName(), IfaceSub.class.getName(),
                IfaceSubSub.class.getName());
    }

    /**
     * Multi criteria.
     */
    @Test
    public void multiCriteria() {
        assertThat(scanResult.getAllClasses().filter(new ClassInfoFilter() {
            @Override
            public boolean accept(final ClassInfo ci) {
                return ci.getInterfaces().getNames().contains(Iface.class.getName())
                        && ci.getSuperclasses().getNames().contains(Impl1.class.getName());
            }
        }).getNames()).containsOnly(Impl1Sub.class.getName(), Impl1SubSub.class.getName());
    }
}
