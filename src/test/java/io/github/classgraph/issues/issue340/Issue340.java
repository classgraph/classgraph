package io.github.classgraph.issues.issue340;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

import nonapi.io.github.classgraph.utils.FastPathResolver;

/**
 * Unit test.
 */
public class Issue340 {
    /** Test. */
    @Test
    public void test() {
        assertThat(FastPathResolver.resolve("", "../../x")).isEqualTo("x");
        assertThat(FastPathResolver.resolve("/", "../../x")).isEqualTo("/x");
        assertThat(FastPathResolver.resolve("/x", "y")).isEqualTo("/x/y");
        assertThat(FastPathResolver.resolve("/x", "../y")).isEqualTo("/y");
        assertThat(FastPathResolver.resolve("/x", "../../y")).isEqualTo("/y");
        assertThat(FastPathResolver.resolve("/x/y/z", "..//..////w")).isEqualTo("/x/w");
        assertThat(FastPathResolver.resolve("/x/y/z", "//p//q")).isEqualTo("/p/q");
    }
}
