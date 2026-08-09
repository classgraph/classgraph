package io.github.classgraph.issues.issue703;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;

/**
 * Test that {@link Object} is scanned like any other class, and that it is recorded as the superclass of standard
 * classes that extend no other class.
 */
public class Issue703Test {
    /** A standard class that extends no other class. */
    public static class ExtendsNothing {
    }

    /** A standard class that extends another standard class. */
    public static class ExtendsSomething extends ExtendsNothing {
    }

    /** An interface, whose classfile names Object as its superclass. */
    public interface Iface {
    }

    /**
     * Object is scanned like any other class, if it is accepted.
     */
    @Test
    public void objectIsScannedIfAccepted() {
        try (var scanResult = new ClassGraph().acceptClasses("java.lang.Object").enableSystemJarsAndModules()
                .enableMethodInfo().scan()) {
            assertThat(scanResult.getAllClasses().getNames()).containsExactly("java.lang.Object");
            final var object = scanResult.getClassInfo("java.lang.Object");
            assertThat(object.getSuperclass()).isNull();
            assertThat(object.getMethodInfo("toString")).isNotEmpty();
        }
    }

    /**
     * Object is the superclass of a standard class that extends no other class, and is the last entry in the
     * superclass chain of any standard class. Interfaces have no superclass.
     */
    @Test
    public void objectIsTheUniversalSuperclass() {
        try (var scanResult = new ClassGraph().acceptPackages(Issue703Test.class.getPackage().getName()).scan()) {
            assertThat(scanResult.getClassInfo(ExtendsNothing.class.getName()).getSuperclass().getName())
                    .isEqualTo("java.lang.Object");
            assertThat(scanResult.getClassInfo(ExtendsSomething.class.getName()).getAllSuperclasses().getNames())
                    .containsExactly(ExtendsNothing.class.getName(), "java.lang.Object");

            // An interface's classfile names Object as its superclass, but interfaces do not
            // extend Object
            assertThat(scanResult.getClassInfo(Iface.class.getName()).getSuperclass()).isNull();
            assertThat(scanResult.getAllSubclasses("java.lang.Object").getNames())
                    .doesNotContain(Iface.class.getName());

            // Only classes that name Object as their superclass in their own classfile are
            // direct subclasses of Object
            assertThat(scanResult.getDirectSubclasses("java.lang.Object").getNames())
                    .contains(ExtendsNothing.class.getName()).doesNotContain(ExtendsSomething.class.getName());
        }
    }
}
