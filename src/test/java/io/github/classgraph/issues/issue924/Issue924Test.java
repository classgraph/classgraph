package io.github.classgraph.issues.issue924;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfoList;

/**
 * Issue 924: there is no separate "getInterfacesImplementing()" method, because
 * {@code getClassesImplementing()} already returns the transitive subinterfaces
 * of an interface as well as the classes that implement it. This test locks in
 * that contract, and the ability to partition the result with
 * {@link ClassInfoList#getInterfaces()} and
 * {@link ClassInfoList#getStandardClasses()}.
 */
public class Issue924Test {
    /** Base interface. */
    public interface A {
    }

    /** Direct subinterface of {@link A}. */
    public interface B extends A {
    }

    /** Transitive subinterface of {@link A}, via {@link B}. */
    public interface C extends B {
    }

    /** Implements the subinterface {@link B}, so is transitively an {@link A}. */
    public static class DImpl implements B {
    }

    /** Implements {@link A} directly. */
    public static class EImpl implements A {
    }

    /**
     * getClassesImplementing() returns transitive subinterfaces as well as
     * implementing classes.
     */
    @Test
    public void getClassesImplementingIncludesSubinterfaces() {
        try (var scanResult = new ClassGraph().acceptPackages(Issue924Test.class.getPackage().getName()).enableAllInfo()
                .scan()) {
            final var implementingA = scanResult.getClassesImplementing(A.class);

            // Both the subinterfaces and the implementing classes are present
            assertThat(implementingA.getNames()).containsExactlyInAnyOrder(B.class.getName(), C.class.getName(),
                    DImpl.class.getName(), EImpl.class.getName());

            // ...and the result can be partitioned into the two
            assertThat(implementingA.getInterfaces().getNames()).containsExactlyInAnyOrder(B.class.getName(),
                    C.class.getName());
            assertThat(implementingA.getStandardClasses().getNames()).containsExactlyInAnyOrder(DImpl.class.getName(),
                    EImpl.class.getName());

            // getSubclasses() does not traverse the interface hierarchy --
            // getClassesImplementing() is the
            // method to use for that
            assertThat(scanResult.getSubclasses(A.class).getNames()).isEmpty();
        }
    }

    /**
     * getSubinterfaces() returns the transitive subinterfaces of an interface, and
     * nothing else.
     */
    @Test
    public void getSubinterfacesReturnsOnlySubinterfaces() {
        try (var scanResult = new ClassGraph().acceptPackages(Issue924Test.class.getPackage().getName()).enableAllInfo()
                .scan()) {
            // Transitive: C extends B extends A
            assertThat(scanResult.getSubinterfaces(A.class).getNames()).containsExactlyInAnyOrder(B.class.getName(),
                    C.class.getName());
            assertThat(scanResult.getSubinterfaces(B.class).getNames()).containsExactlyInAnyOrder(C.class.getName());
            assertThat(scanResult.getSubinterfaces(C.class).getNames()).isEmpty();

            // The by-name overload and ClassInfo#getSubinterfaces() give the same answer
            assertThat(scanResult.getSubinterfaces(A.class.getName()).getNames())
                    .containsExactlyInAnyOrder(B.class.getName(), C.class.getName());
            assertThat(scanResult.getClassInfo(A.class.getName()).getSubinterfaces().getNames())
                    .containsExactlyInAnyOrder(B.class.getName(), C.class.getName());

            // Implementing classes are not subinterfaces, and a standard class has no
            // subinterfaces
            assertThat(scanResult.getSubinterfaces(A.class).getNames()).doesNotContain(DImpl.class.getName(),
                    EImpl.class.getName());
            assertThat(scanResult.getClassInfo(DImpl.class.getName()).getSubinterfaces()).isEmpty();

            // An interface that was never scanned has no subinterfaces
            assertThat(scanResult.getSubinterfaces("com.xyz.NonExistent")).isEmpty();
        }
    }
}
