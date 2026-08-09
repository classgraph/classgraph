package io.github.classgraph.test;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;
import io.github.classgraph.test.accepted.Cls;
import io.github.classgraph.test.accepted.ClsSub;
import io.github.classgraph.test.accepted.ClsSubSub;
import io.github.classgraph.test.accepted.Iface;
import io.github.classgraph.test.accepted.IfaceSub;
import io.github.classgraph.test.accepted.IfaceSubSub;
import io.github.classgraph.test.accepted.Impl1;
import io.github.classgraph.test.accepted.Impl1Sub;
import io.github.classgraph.test.accepted.Impl1SubSub;
import io.github.classgraph.test.accepted.Impl2;
import io.github.classgraph.test.accepted.Impl2Sub;
import io.github.classgraph.test.accepted.Impl2SubSub;

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
        scanResult = new ClassGraph().acceptPackages(Impl1.class.getPackage().getName()).scan();
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
        final var impls = scanResult.getClassInfo(Iface.class.getName()).getAllClassesImplementing().getNames();
        assertThat(impls.contains(Impl1.class.getName())).isTrue();
    }

    /**
     * Filter.
     */
    @Test
    public void filter() {
        assertThat(scanResult.getAllClasses().filter(ci -> ci.getName().contains("ClsSub")).getNames())
                .containsOnly(ClsSub.class.getName(), ClsSubSub.class.getName());
    }

    /**
     * Stream has super interface direct.
     */
    @Test
    public void streamHasSuperInterfaceDirect() {
        assertThat(scanResult.getAllClasses()
                .filter(ci -> ci.getDirectInterfaces().getNames().contains(Iface.class.getName())).getNames())
                .containsOnly(IfaceSub.class.getName(), Impl2.class.getName());
    }

    /**
     * Stream has super interface.
     */
    @Test
    public void streamHasSuperInterface() {
        assertThat(scanResult.getAllClasses()
                .filter(ci -> ci.getAllInterfaces().getNames().contains(Iface.class.getName())).getNames())
                .containsOnly(IfaceSub.class.getName(), IfaceSubSub.class.getName(), Impl2.class.getName(),
                        Impl2Sub.class.getName(), Impl2SubSub.class.getName(), Impl1.class.getName(),
                        Impl1Sub.class.getName(), Impl1SubSub.class.getName());
    }

    /**
     * Implements interface direct.
     */
    @Test
    public void implementsInterfaceDirect() {
        assertThat(scanResult.getDirectClassesImplementing(Iface.class).getNames())
                .containsOnly(IfaceSub.class.getName(), Impl2.class.getName());
    }

    /**
     * Implements interface.
     */
    @Test
    public void implementsInterface() {
        assertThat(scanResult.getAllClassesImplementing(Iface.class).getNames()).containsOnly(Impl1.class.getName(),
                Impl1Sub.class.getName(), Impl1SubSub.class.getName(), Impl2.class.getName(), Impl2Sub.class.getName(),
                Impl2SubSub.class.getName(), IfaceSub.class.getName(), IfaceSubSub.class.getName());
    }

    /**
     * Direct vs. transitive subclasses.
     */
    @Test
    public void directVsAllSubclasses() {
        assertThat(scanResult.getDirectSubclasses(Impl1.class).getNames()).containsOnly(Impl1Sub.class.getName());
        assertThat(scanResult.getAllSubclasses(Impl1.class).getNames()).containsOnly(Impl1Sub.class.getName(),
                Impl1SubSub.class.getName());
        assertThat(scanResult.getClassInfo(Impl1.class.getName()).getDirectSubclasses().getNames())
                .containsOnly(Impl1Sub.class.getName());

        // The subclasses of Object are the classes with no other superclass
        assertThat(scanResult.getDirectSubclasses(Object.class).getNames()).contains(Cls.class.getName(),
                Impl1.class.getName(), Impl2.class.getName());
        assertThat(scanResult.getDirectSubclasses(Object.class).getNames()).doesNotContain(ClsSub.class.getName(),
                Impl1Sub.class.getName());
    }

    /**
     * Direct vs. transitive superclasses.
     */
    @Test
    public void directVsAllSuperclasses() {
        assertThat(scanResult.getAllSuperclasses(ClsSubSub.class).getNames()).containsOnly(ClsSub.class.getName(),
                Cls.class.getName(), "java.lang.Object");
        assertThat(scanResult.getClassInfo(ClsSubSub.class.getName()).getSuperclass().getName())
                .isEqualTo(ClsSub.class.getName());
    }

    /**
     * Direct vs. transitive interfaces, looked up through {@link ScanResult}.
     */
    @Test
    public void directVsAllInterfaces() {
        assertThat(scanResult.getDirectInterfaces(Impl2SubSub.class).getNames())
                .containsOnly(IfaceSubSub.class.getName());
        assertThat(scanResult.getAllInterfaces(Impl2SubSub.class).getNames()).containsOnly(IfaceSubSub.class.getName(),
                IfaceSub.class.getName(), Iface.class.getName());
    }

    /**
     * Direct vs. transitive subinterfaces.
     */
    @Test
    public void directVsAllSubinterfaces() {
        assertThat(scanResult.getDirectSubinterfaces(Iface.class).getNames()).containsOnly(IfaceSub.class.getName());
        assertThat(scanResult.getAllSubinterfaces(Iface.class).getNames()).containsOnly(IfaceSub.class.getName(),
                IfaceSubSub.class.getName());
        assertThat(scanResult.getClassInfo(Iface.class.getName()).getDirectSubinterfaces().getNames())
                .containsOnly(IfaceSub.class.getName());
    }

    /**
     * Multi criteria.
     */
    @Test
    public void multiCriteria() {
        assertThat(scanResult.getAllClasses()
                .filter(ci -> ci.getAllInterfaces().getNames().contains(Iface.class.getName())
                        && ci.getAllSuperclasses().getNames().contains(Impl1.class.getName()))
                .getNames()).containsOnly(Impl1Sub.class.getName(), Impl1SubSub.class.getName());
    }
}
