package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import io.github.classgraph.base.LogNode;
import io.github.classgraph.classpath.Classpath;
import io.github.classgraph.vfs.Vfs;

/**
 * Every public method and field in an exported package whose type is a {@code java.util} collection interface is
 * annotated {@code @Unmodifiable}, so that Kotlin 2.3.20 and later maps it to a read-only Kotlin type rather than a
 * platform type (#948). The annotation is {@code CLASS}-retained, so ClassGraph's own scan of its classes can check
 * this.
 */
class UnmodifiableAnnotationTest {
    /** The name of the annotation. */
    private static final String UNMODIFIABLE = "org.jetbrains.annotations.Unmodifiable";

    /** The packages exported by the module descriptors. */
    private static final String[] EXPORTED_PACKAGES = { "io.github.classgraph", "io.github.classgraph.base",
            "io.github.classgraph.vfs", "io.github.classgraph.vfs.reader", "io.github.classgraph.classpath" };

    /** The collection interfaces that Kotlin maps to a read-only type when they are annotated. */
    private static final Set<String> COLLECTION_TYPES = Set.of("java.lang.Iterable", "java.util.Collection",
            "java.util.Iterator", "java.util.List", "java.util.ListIterator", "java.util.Map",
            "java.util.NavigableMap", "java.util.NavigableSet", "java.util.Set", "java.util.SortedMap",
            "java.util.SortedSet");

    /**
     * The members that deliberately return a collection that is not annotated. ClassLoaderHandler is implemented by
     * users, whose implementations may return a mutable list (ClassGraph copies it), and
     * VfsFileSystemProvider#readAttributes overrides a JDK method, and returns a new mutable map.
     */
    private static final Set<String> NOT_ANNOTATED = Set.of(
            "io.github.classgraph.classpath.ClassLoaderHandler.getPackageRootPrefixes",
            "io.github.classgraph.classpath.ClassLoaderHandler.getLibDirPrefixes",
            "io.github.classgraph.vfs.VfsFileSystemProvider.readAttributes");

    /** The members whose type is a collection interface. */
    private int numCollectionMembers;

    /** The members whose type is a collection interface without the annotation. */
    private final List<String> unannotated = new ArrayList<>();

    /**
     * Check the type of a member.
     *
     * @param memberName
     *            the fully qualified name of the member.
     * @param type
     *            the type of the member.
     */
    private void check(final String memberName, final TypeSignature type) {
        if (type instanceof final ClassRefTypeSignature classRef
                && COLLECTION_TYPES.contains(classRef.getFullyQualifiedClassName())) {
            numCollectionMembers++;
            final AnnotationInfoList annotations = classRef.getTypeAnnotationInfo();
            if (annotations == null || !annotations.containsName(UNMODIFIABLE)) {
                unannotated.add(memberName);
            }
        }
    }

    /** Every collection-typed member of the public API is annotated, except for the members listed above. */
    @Test
    void collectionTypesAreAnnotated() {
        // The jarfiles or directories of the main classes of each module, which leaves out the test classes
        final var locations = Stream.of(LogNode.class, Vfs.class, Classpath.class, ClassGraph.class)
                .map(cls -> cls.getProtectionDomain().getCodeSource().getLocation()).toList();
        try (ScanResult scanResult = new ClassGraph().enableClasspathEntries(locations).enableClassInfo()
                .enableMethodInfo().enableFieldInfo().enableAnnotationInfo()
                .acceptPackagesNonRecursive(EXPORTED_PACKAGES).scan()) {
            for (final ClassInfo classInfo : scanResult.getAllClasses()) {
                if (!classInfo.isPublic()) {
                    continue;
                }
                for (final MethodInfo methodInfo : classInfo.getDeclaredMethodInfo()) {
                    if (!methodInfo.isSynthetic() && !methodInfo.isBridge()) {
                        check(classInfo.getName() + "." + methodInfo.getName(),
                                methodInfo.getTypeSignatureOrTypeDescriptor().getResultType());
                    }
                }
                for (final FieldInfo fieldInfo : classInfo.getDeclaredFieldInfo()) {
                    check(classInfo.getName() + "." + fieldInfo.getName(),
                            fieldInfo.getTypeSignatureOrTypeDescriptor());
                }
            }
        }
        // Guards against a scan that finds nothing, which would pass trivially
        assertThat(numCollectionMembers).isGreaterThan(50);
        assertThat(unannotated).containsExactlyInAnyOrderElementsOf(NOT_ANNOTATED);
    }
}
