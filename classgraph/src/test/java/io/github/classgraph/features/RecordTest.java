package io.github.classgraph.features;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URL;
import java.net.URLClassLoader;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;

/**
 * RecordTest.
 */
public class RecordTest {
    /** The jar URL. */
    private static final URL jarURL = RecordTest.class.getClassLoader().getResource("record.jar");

    /**
     * Test records (JDK 14+).
     *
     * @throws Exception
     *             the exception
     */
    @Test
    public void recordJar() throws Exception {
        try (var classLoader = new URLClassLoader(new URL[] { jarURL });
                var scanResult = new ClassGraph().enableClassLoaders(classLoader).acceptPackages("pkg")
                        .enableAllInfo().scan()) {
            final var classInfoList = scanResult.getAllRecords();
            assertThat(classInfoList.getNames()).containsExactly("pkg.Record");
            final var fieldInfo = classInfoList.get(0).getFieldInfo("x");
            assertThat(fieldInfo).isNotNull();
        }
    }
}
