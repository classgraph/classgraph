package io.github.classgraph.features;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URL;
import java.net.URLClassLoader;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ClassInfoList;
import io.github.classgraph.FieldInfo;
import io.github.classgraph.ScanResult;

/**
 * MultiReleaseJar.
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
        try (ScanResult scanResult = new ClassGraph().overrideClassLoaders(new URLClassLoader(new URL[] { jarURL }))
                .enableAllInfo().scan()) {
            final ClassInfoList classInfoList = scanResult.getAllRecords();
            assertThat(classInfoList).isNotEmpty();
            final ClassInfo classInfo = classInfoList.get(0);
            final FieldInfo fieldInfo = classInfo.getFieldInfo("x");
            assertThat(fieldInfo).isNotNull();
        }
    }
}
