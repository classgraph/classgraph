package io.github.classgraph.issues.issue772;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

/**
 * Tests the order of classes overriding one another when selecting methods
 */
public class MethodOverrideOrderTest {

    private static ScanResult scanResult;

    @BeforeAll
    public static void setup() {
        scanResult = new ClassGraph().acceptPackages(MethodOverrideOrderTest.class.getPackage().getName())
                .enableMethodInfo().scan();
    }

    @AfterAll
    public static void teardown() {
        scanResult.close();
        scanResult = null;
    }

    /**
     * Tests if the correct method is selected if a class implements from two
     * interfaces that inherit from another. Case of the child class implementing
     * the inherited interface.
     */
    @Test
    public void interfaceMethodOrderingA() {
        final var classInfo = scanResult.getClassInfo("io.github.classgraph.issues.issue772.ExampleA$Child");
        assertThat(classInfo).isNotNull();
        final var closeMethods = classInfo.getMethodInfo("close");
        assertThat(closeMethods.size()).isEqualTo(1);
        assertThat(closeMethods.get(0).getClassInfo().getName())
                .isEqualTo("io.github.classgraph.issues.issue772.MyCloseable");
    }

    /**
     * Tests if the correct method is selected if a class implements from two
     * interfaces that inherit from another. Case of the child class implementing
     * the inherited interface.
     */
    @Test
    public void interfaceMethodOrderingB() {
        final var classInfo = scanResult.getClassInfo("io.github.classgraph.issues.issue772.ExampleB$Child");
        assertThat(classInfo).isNotNull();
        final var closeMethods = classInfo.getMethodInfo("close");
        assertThat(closeMethods.size()).isEqualTo(1);
        assertThat(closeMethods.get(0).getClassInfo().getName())
                .isEqualTo("io.github.classgraph.issues.issue772.MyCloseable");
    }

    /**
     * Tests if the correct method is selected if a class implements from two
     * interfaces that inherit from another. Case of the child class implementing
     * the inherited interface.
     */
    @Test
    public void interfaceMethodOrderingC() {
        final var classInfo = scanResult.getClassInfo("io.github.classgraph.issues.issue772.ExampleC$Child");
        assertThat(classInfo).isNotNull();
        final var closeMethods = classInfo.getMethodInfo("close");
        assertThat(closeMethods.size()).isEqualTo(1);
        assertThat(closeMethods.get(0).getClassInfo().getName())
                .isEqualTo("io.github.classgraph.issues.issue772.ExampleC");
    }

}
