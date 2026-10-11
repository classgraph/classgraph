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
                        .enableClassInfo().enableFieldInfo().enableMethodInfo().enableAnnotationInfo()
                        .enableStaticFinalFieldConstantInitializerValues().ignoreClassVisibility()
                        .ignoreFieldVisibility().ignoreMethodVisibility().scan()) {
            final var classInfoList = scanResult.getAllRecords();
            assertThat(classInfoList.getNames()).containsExactly("pkg.Record");
            final var fieldInfo = classInfoList.get(0).getFieldInfo("x");
            assertThat(fieldInfo).isNotNull();
            // A record's classfile has an InnerClasses entry for MethodHandles$Lookup, because its bootstrap
            // methods take a Lookup; that entry is not linked, since Lookup is neither nested in the record nor
            // brought into the class graph by it
            assertThat(classInfoList.get(0).hasNestedClasses()).isFalse();
            assertThat(classInfoList.get(0).isNestedClass()).isFalse();
            assertThat(scanResult.getClassInfo("java.lang.invoke.MethodHandles$Lookup")).isNull();
            assertThat(scanResult.getClassInfo("java.lang.invoke.MethodHandles")).isNull();
        }
    }

    /**
     * A record depends on {@code MethodHandles$Lookup}, since its bootstrap methods take a Lookup, so with
     * inter-class dependencies enabled, the record's InnerClasses entry for Lookup is linked, as for any other
     * nested class that is a dependency.
     *
     * @throws Exception
     *             the exception
     */
    @Test
    public void recordDependsOnMethodHandlesLookup() throws Exception {
        try (var classLoader = new URLClassLoader(new URL[] { jarURL });
                var scanResult = new ClassGraph().enableClassLoaders(classLoader).acceptPackages("pkg")
                        .enableClassInfo().enableInterClassDependencies().enableExternalClasses()
                        .ignoreClassVisibility().scan()) {
            final var record = scanResult.getClassInfo("pkg.Record");
            assertThat(record.getClassDependencies().getNames()).contains("java.lang.invoke.MethodHandles$Lookup");
            assertThat(record.hasNestedClasses()).isFalse();
            assertThat(record.isNestedClass()).isFalse();
            final var lookup = scanResult.getClassInfo("java.lang.invoke.MethodHandles$Lookup");
            assertThat(lookup.getResource()).isNull();
            assertThat(lookup.isMemberClass()).isTrue();
            assertThat(lookup.getEnclosingClasses().getNames()).containsExactly("java.lang.invoke.MethodHandles");
        }
    }

    /**
     * The InnerClasses entry for {@code MethodHandles$Lookup} in the classfiles of MethodHandles and the classes
     * nested in it describes the real nesting.
     */
    @Test
    public void methodHandlesLookupIsNestedInMethodHandles() {
        try (var scanResult = new ClassGraph().enableSystemModules().enableClassInfo()
                .acceptClasses("java.lang.invoke.MethodHandles", "java.lang.invoke.MethodHandles$Lookup")
                .ignoreClassVisibility().scan()) {
            final var lookup = scanResult.getClassInfo("java.lang.invoke.MethodHandles$Lookup");
            assertThat(lookup).isNotNull();
            assertThat(lookup.isStatic()).isTrue();
            assertThat(lookup.isMemberClass()).isTrue();
            assertThat(lookup.getEnclosingClasses().getNames()).containsExactly("java.lang.invoke.MethodHandles");
            assertThat(scanResult.getClassInfo("java.lang.invoke.MethodHandles").getNestedClasses().getNames())
                    .contains("java.lang.invoke.MethodHandles$Lookup");
        }
    }
}
