package io.github.classgraph.features;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.function.UnaryOperator;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.base.LogNode;

/**
 * No config method turns on another config method, so an option that is only read by another option has to be asked
 * for alongside it. A scan whose config does not say that is refused, rather than silently ignoring the option.
 */
public class ExplicitConfigTest {
    /** A class whose only dependency beyond its enclosing class is the type of its one field. */
    public static class FieldOnly {
        /** A field whose type is a dependency of {@link FieldOnly}, but only if the fields are read. */
        @SuppressWarnings("unused")
        private Number fieldType;
    }

    /**
     * Check that a config option is refused without the option that reads it.
     *
     * @param config
     *            the config that names the option but not its companion.
     * @param message
     *            the expected exception message.
     */
    private static void assertRefused(final UnaryOperator<ClassGraph> config, final String message) {
        final var classGraph = config.apply(new ClassGraph());
        assertThatIllegalArgumentException().isThrownBy(classGraph::scan).withMessage(message);
    }

    /** The options that are read only when the class info is read need {@code enableClassInfo()}. */
    @Test
    public void theInfoOptionsNeedClassInfo() {
        assertRefused(ClassGraph::enableFieldInfo, "ClassGraph#enableFieldInfo() has no effect unless "
                + "ClassGraph#enableClassInfo() is also called");
        assertRefused(ClassGraph::enableMethodInfo, "ClassGraph#enableMethodInfo() has no effect unless "
                + "ClassGraph#enableClassInfo() is also called");
        assertRefused(ClassGraph::enableAnnotationInfo, "ClassGraph#enableAnnotationInfo() has no effect unless "
                + "ClassGraph#enableClassInfo() is also called");
        assertRefused(ClassGraph::ignoreClassVisibility, "ClassGraph#ignoreClassVisibility() has no effect unless "
                + "ClassGraph#enableClassInfo() is also called");
        assertRefused(ClassGraph::enableInterClassDependencies,
                "ClassGraph#enableInterClassDependencies() has no effect unless "
                        + "ClassGraph#enableClassInfo() is also called");
        assertRefused(ClassGraph::enableExternalClasses, "ClassGraph#enableExternalClasses() has no effect unless "
                + "ClassGraph#enableClassInfo() is also called");
    }

    /** The options that are read only when the fields or methods are read need those. */
    @Test
    public void theVisibilityOptionsNeedTheMembersTheyApplyTo() {
        assertRefused(classGraph -> classGraph.enableClassInfo().ignoreFieldVisibility(),
                "ClassGraph#ignoreFieldVisibility() has no effect unless ClassGraph#enableFieldInfo() is also "
                        + "called");
        assertRefused(classGraph -> classGraph.enableClassInfo().enableStaticFinalFieldConstantInitializerValues(),
                "ClassGraph#enableStaticFinalFieldConstantInitializerValues() has no effect unless "
                        + "ClassGraph#enableFieldInfo() is also called");
        assertRefused(classGraph -> classGraph.enableClassInfo().ignoreMethodVisibility(),
                "ClassGraph#ignoreMethodVisibility() has no effect unless ClassGraph#enableMethodInfo() is also "
                        + "called");
    }

    /** Realtime logging says when the log is written, so there has to be a log. */
    @Test
    public void realtimeLoggingNeedsVerbose() {
        try {
            assertRefused(ClassGraph::enableRealtimeLogging, "ClassGraph#enableRealtimeLogging() has no effect "
                    + "unless ClassGraph#verbose() is also called");
        } finally {
            // Realtime logging is a global setting, so switch it off again
            LogNode.logInRealtime(false);
        }
    }

    /** The module layer options say where to look for modules, so a kind of module has to be enabled. */
    @Test
    public void theModuleLayerOptionsNeedAKindOfModule() {
        final var message = " has no effect unless ClassGraph#enableSystemModules() or "
                + "ClassGraph#enableNonSystemModules() is also called";
        assertRefused(classGraph -> classGraph.enableModuleLayers(ModuleLayer.boot()),
                "ClassGraph#enableModuleLayers()" + message);
        assertRefused(ClassGraph::enableDetectedModuleLayers, "ClassGraph#enableDetectedModuleLayers()" + message);
        assertRefused(ClassGraph::ignoreParentModuleLayers, "ClassGraph#ignoreParentModuleLayers()" + message);
    }

    /**
     * Inter-class dependencies are found with the class info alone: the dependencies that the members name are
     * simply not among them, since those members were not read.
     */
    @Test
    public void interClassDependenciesAreFoundWithoutTheMemberInfo() {
        final var fieldOnly = FieldOnly.class.getName();
        try (var scanResult = new ClassGraph().enableClasspath().acceptClasses(fieldOnly).enableClassInfo()
                .enableExternalClasses().enableInterClassDependencies().scan()) {
            // The enclosing class is named by the classfile itself, but the field's type is named only by the
            // field, which was not read
            assertThat(scanResult.getClassInfo(fieldOnly).getClassDependencies().getNames())
                    .containsExactly(ExplicitConfigTest.class.getName());
        }

        // Reading the fields as well finds the field's type
        try (var scanResult = new ClassGraph().enableClasspath().acceptClasses(fieldOnly).enableClassInfo()
                .enableFieldInfo().ignoreFieldVisibility().enableExternalClasses().enableInterClassDependencies()
                .scan()) {
            assertThat(scanResult.getClassInfo(fieldOnly).getClassDependencies().getNames())
                    .containsExactlyInAnyOrder(ExplicitConfigTest.class.getName(), Number.class.getName());
        }
    }
}
