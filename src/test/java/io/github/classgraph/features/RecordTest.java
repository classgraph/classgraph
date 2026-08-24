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
        try (URLClassLoader classLoader = new URLClassLoader(new URL[] { jarURL });
                ScanResult scanResult = new ClassGraph().overrideClassLoaders(classLoader).acceptPackages("pkg")
                        .enableAllInfo().scan()) {
            final ClassInfoList classInfoList = scanResult.getAllRecords();
            assertThat(classInfoList.getNames()).containsExactly("pkg.Record");
            final ClassInfo classInfo = classInfoList.get(0);
            final FieldInfo fieldInfo = classInfo.getFieldInfo("x");
            assertThat(fieldInfo).isNotNull();
        }
    }
}
