package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URL;

import org.junit.jupiter.api.Test;

/**
 * The record components rendered by {@link ClassInfo#toString()} are the record's own instance fields, and
 * rendering a record does not require field info to have been enabled.
 */
public class RecordComponentRenderingTest {
    /** A precompiled jar holding {@code pkg2.Config}, a record with two components and one static field. */
    private static final URL JAR_URL = RecordComponentRenderingTest.class.getClassLoader()
            .getResource("record-static-field.jar");

    /** A static field of a record is not one of its components, so is not rendered in the component list. */
    @Test
    public void staticFieldOfRecordIsNotAComponent() {
        try (ScanResult scanResult = new ClassGraph().overrideClasspath(JAR_URL).acceptPackages("pkg2")
                .enableAllInfo().scan()) {
            assertThat(scanResult.getClassInfo("pkg2.Config").toString())
                    .isEqualTo("public final record pkg2.Config(java.lang.String name, int port)"
                            + " extends java.lang.Record");
        }
    }

    /**
     * The components of a record are read from its field info, so if field info was not enabled, the component list
     * is omitted rather than rendered empty -- and rendering the record does not throw.
     */
    @Test
    public void recordWithoutFieldInfoRendersNoComponents() {
        try (ScanResult scanResult = new ClassGraph().overrideClasspath(JAR_URL).acceptPackages("pkg2")
                .enableClassInfo().scan()) {
            assertThat(scanResult.getClassInfo("pkg2.Config").toString())
                    .isEqualTo("public final record pkg2.Config extends java.lang.Record");
        }
    }
}
