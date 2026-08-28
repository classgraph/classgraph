package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Tests for {@link AutomaticModuleName}. */
public class AutomaticModuleNameTest {
    /**
     * The automatic module name is derived from the jar's leafname, following the algorithm documented by
     * {@link java.lang.module.ModuleFinder#of(java.nio.file.Path...)}: the extension and any version suffix are
     * dropped, and the remaining non-alphanumeric characters become dots.
     */
    @Test
    public void automaticModuleNamesAreDerivedFromTheJarName() {
        assertThat(AutomaticModuleName.derive("/a/b/foo.jar")).isEqualTo("foo");
        assertThat(AutomaticModuleName.derive("foo.jar")).isEqualTo("foo");
        assertThat(AutomaticModuleName.derive("/a/b/commons-lang3-3.12.0.jar")).isEqualTo("commons.lang3");
        assertThat(AutomaticModuleName.derive("/a/b/my_lib.jar")).isEqualTo("my.lib");
        // Leading, trailing and repeated dots are all collapsed away
        assertThat(AutomaticModuleName.derive("/a/b/-foo--bar-.jar")).isEqualTo("foo.bar");
        // A jar nested inside another jar is named after the inner jar
        assertThat(AutomaticModuleName.derive("/a/outer.jar!/BOOT-INF/lib/inner-1.0.jar")).isEqualTo("inner");
        // A package root within a jar is named after the jar that contains it, not after the package root
        assertThat(AutomaticModuleName.derive("/a/outer.jar!/BOOT-INF/classes")).isEqualTo("outer");
        // A '!' that is not followed by '/' is part of a name, not a separator, so it ends neither (#903)
        assertThat(AutomaticModuleName.derive("/a/outer.jar!/dir!name/classes")).isEqualTo("outer");
        assertThat(AutomaticModuleName.derive("/a/outer.jar!/lib/we!rd-1.0.jar")).isEqualTo("we.rd");
    }
}
