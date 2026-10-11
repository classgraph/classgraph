package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.classgraph.vfs.VfsEntry;

/**
 * ClassGraph's public classes are not designed to be extended by user code, so each one is final or sealed.
 */
public class PublicClassesAreFinalTest {
    /**
     * Every public class outside an internal package is final or sealed, unless it is nested in a class that is not
     * public, since user code in another package cannot reach such a class. Interfaces, annotations, enums and
     * records are not checked.
     *
     * @throws ClassNotFoundException
     *             if a scanned class cannot be loaded
     */
    @Test
    public void publicClassesAreFinalOrSealed() throws ClassNotFoundException {
        final List<String> checked = new ArrayList<>();
        final List<String> notFinalOrSealed = new ArrayList<>();
        // ignoreClassVisibility() is needed so that a non-public enclosing class is scanned, and so is returned by
        // getEnclosingClasses()
        try (var scanResult = new ClassGraph().enableClasspath().acceptPackages("io.github.classgraph")
                .enableClassInfo().ignoreClassVisibility().scan()) {
            for (final ClassInfo classInfo : scanResult.getAllClasses()) {
                final var file = classInfo.getClasspathElementFile();
                if (file == null || file.getPath().contains("test-classes")
                        || classInfo.getPackageName().contains(".internal") || !classInfo.isPublic()
                        || !classInfo.getEnclosingClasses().stream().allMatch(ClassInfo::isPublic)
                        || classInfo.isInterface() || classInfo.isAnnotation() || classInfo.isEnum()
                        || classInfo.isRecord()) {
                    continue;
                }
                checked.add(classInfo.getName());
                if (classInfo.isFinal()) {
                    continue;
                }
                // ClassGraph does not read the PermittedSubclasses attribute, so load the class to see if it is
                // sealed
                if (!Class.forName(classInfo.getName(), false, getClass().getClassLoader()).isSealed()) {
                    notFinalOrSealed.add(classInfo.getName());
                }
            }
        }
        // If the scan stopped finding the main classes (for example because they were on the module path, or in a
        // package that the scan no longer accepts), the last assertion would pass without checking anything, so first
        // check that some known final and sealed classes from more than one module were checked
        assertThat(checked).contains(ClassGraph.class.getName(), ClassInfo.class.getName(),
                VfsEntry.class.getName());
        assertThat(notFinalOrSealed).isEmpty();
    }
}
