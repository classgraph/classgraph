package io.github.classgraph.features;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

/**
 * A local record, enum or interface is implicitly static. Only its own {@code InnerClasses} entry has the
 * {@code ACC_STATIC} flag, and that entry has no outer class, since the class is local. The classes are scanned from
 * the classfiles in {@code src/test/resources/io/github/classgraph/features/localrecord}, which were compiled from
 * the {@code LocalRecordHolder.java} next to them, since local records, enums and interfaces need Java 16.
 */
public class LocalRecordTest {
    /** The package of the precompiled classfiles. */
    private static final String PKG = "io.github.classgraph.features.localrecord";

    /** A local record, enum or interface is static, and a local class declared in an instance method is not. */
    @Test
    public void localRecordEnumAndInterfaceAreStatic() {
        try (ScanResult scanResult = new ClassGraph().acceptPackages(PKG).ignoreClassVisibility().scan()) {
            for (final String name : new String[] { "LocalRecord", "LocalEnum", "LocalInterface" }) {
                assertThat(scanResult.getClassInfo(PKG + ".LocalRecordHolder$1" + name).isStatic()).as(name)
                        .isTrue();
            }
            assertThat(scanResult.getClassInfo(PKG + ".LocalRecordHolder$1LocalClass").isStatic()).isFalse();
        }
    }
}
