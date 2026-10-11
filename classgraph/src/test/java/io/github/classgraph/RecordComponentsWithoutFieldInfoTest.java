package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The names of the record components are read from the {@code Record} attribute whether or not field info is
 * enabled. Without field info the types of the components are not known, so a record with components is rendered
 * without its component list, but a record with no components still renders {@code "()"}.
 */
public class RecordComponentsWithoutFieldInfoTest {
    /** A record with no components. */
    public record Empty() {
    }

    /** A record with two components. */
    public record Point(int x, int y) {
    }

    /**
     * Without field info, a record with no components renders {@code "()"}, and a record with components omits its
     * component list rather than render it empty.
     */
    @Test
    public void recordsWithoutFieldInfo() {
        try (var scanResult = new ClassGraph().enableClasspath().enableClassInfo()
                .acceptClasses(Empty.class.getName(), Point.class.getName()).scan()) {
            final var empty = scanResult.getClassInfo(Empty.class.getName());
            assertThat(empty).isNotNull();
            assertThat(empty.isRecord()).isTrue();
            assertThat(empty.toString()).isEqualTo("public static final record " + Empty.class.getName() + "()");
            final var point = scanResult.getClassInfo(Point.class.getName());
            assertThat(point).isNotNull();
            assertThat(point.isRecord()).isTrue();
            assertThat(point.toString()).isEqualTo("public static final record " + Point.class.getName());
        }
    }
}
