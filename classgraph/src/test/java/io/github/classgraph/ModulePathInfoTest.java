package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;

import org.junit.jupiter.api.Test;

import nonapi.io.github.classgraph.reflection.ReflectionUtils;

/**
 * Tests for {@link ModulePathInfo}. Only the {@code Add-Exports} and {@code Add-Opens} entries can be filled in
 * without launching a JVM with module switches on its commandline, since the rest are only read from the
 * commandline.
 */
public class ModulePathInfoTest {
    /** A new instance has nothing in it, and prints as the empty string rather than as a bare switch. */
    @Test
    public void aNewInstanceIsEmpty() {
        final var modulePathInfo = new ModulePathInfo();
        assertThat(modulePathInfo.getModulePath()).isEmpty();
        assertThat(modulePathInfo.getAddModules()).isEmpty();
        assertThat(modulePathInfo.getPatchModules()).isEmpty();
        assertThat(modulePathInfo.getAddExports()).isEmpty();
        assertThat(modulePathInfo.getAddOpens()).isEmpty();
        assertThat(modulePathInfo.getAddReads()).isEmpty();
        assertThat(modulePathInfo).hasToString("");
    }

    /** Every getter returns an unmodifiable view, so that a caller cannot change the scan result. */
    @Test
    public void gettersReturnUnmodifiableSets() {
        final var modulePathInfo = new ModulePathInfo();
        assertThatThrownBy(() -> modulePathInfo.getModulePath().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> modulePathInfo.getAddModules().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> modulePathInfo.getPatchModules().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> modulePathInfo.getAddExports().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> modulePathInfo.getAddOpens().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> modulePathInfo.getAddReads().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /**
     * The {@code Add-Exports} and {@code Add-Opens} manifest entries found during scanning are added to the
     * corresponding sets, in the order they were found, and each is printed as its own commandline switch.
     */
    @Test
    public void manifestEntriesAreAddedAndPrintedAsSwitches() {
        final var modulePathInfo = new ModulePathInfo();
        modulePathInfo.addExportsEntry("jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED");
        modulePathInfo.addExportsEntry("java.base/sun.nio.ch=ALL-UNNAMED");
        // A repeated entry is only listed once, since the entries are held in a set
        modulePathInfo.addExportsEntry("java.base/sun.nio.ch=ALL-UNNAMED");
        modulePathInfo.addOpensEntry("java.base/java.lang=ALL-UNNAMED");

        assertThat(modulePathInfo.getAddExports()).containsExactly(
                "jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED", "java.base/sun.nio.ch=ALL-UNNAMED");
        assertThat(modulePathInfo.getAddOpens()).containsExactly("java.base/java.lang=ALL-UNNAMED");
        assertThat(modulePathInfo).hasToString("--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED "
                + "--add-exports=java.base/sun.nio.ch=ALL-UNNAMED "
                + "--add-opens=java.base/java.lang=ALL-UNNAMED");
    }

    /**
     * The module switches this JVM was launched with are read from the runtime, without the reflective call to
     * {@code java.lang.management} failing on a runtime that has it. Which switches are found depends on how the
     * test JVM was launched, so the contents are not asserted on here; reading them a second time is a no-op, and
     * leaves what was already read in place.
     */
    @Test
    public void theCommandlineSwitchesAreReadFromTheRuntime() {
        final var modulePathInfo = new ClassGraph().getModulePathInfo();
        final var addOpensAfterFirstRead = Set.copyOf(modulePathInfo.getAddOpens());
        modulePathInfo.getRuntimeInfo(new ReflectionUtils());
        assertThat(modulePathInfo.getAddOpens()).isEqualTo(addOpensAfterFirstRead);
    }
}
