package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

/**
 * Checks that every collection handed back by the public API is unmodifiable, by reflectively calling all public
 * no-arg methods that return a {@link List} or a {@link Map}, on a real scan of this test tree.
 *
 * <p>
 * This is a completeness check: it will fail if a new collection-returning accessor is added without freezing the
 * collection it returns, which a hand-written test for each accessor would not catch.
 */
public class ReturnedListsAreUnmodifiableTest {
    /** The objects whose accessors are exercised, gathered from a scan. */
    private static List<Object> scanResultObjects(final ScanResult scanResult) {
        final List<Object> objects = new ArrayList<>();
        objects.add(scanResult);
        final ClassInfo classInfo = scanResult.getClassInfo(ClassWithMembers.class.getName());
        assertThat(classInfo).isNotNull();
        objects.add(classInfo);
        objects.add(classInfo.getPackageInfo());
        objects.addAll(classInfo.getDeclaredFieldInfo());
        objects.addAll(classInfo.getDeclaredMethodInfo());
        objects.addAll(classInfo.getAllAnnotationInfo());
        for (final MethodInfo methodInfo : classInfo.getDeclaredMethodInfo()) {
            objects.addAll(methodInfo.getParameterInfo());
            addTypeSignature(methodInfo.getTypeSignatureOrTypeDescriptor(), objects);
        }
        for (final FieldInfo fieldInfo : classInfo.getDeclaredFieldInfo()) {
            addTypeSignature(fieldInfo.getTypeSignatureOrTypeDescriptor(), objects);
        }
        for (final AnnotationInfo annotationInfo : classInfo.getAllAnnotationInfo()) {
            objects.addAll(annotationInfo.getParameterValues());
        }
        addTypeSignature(classInfo.getTypeSignature(), objects);

        // Also sweep the returned lists themselves, since they have no-arg accessors of their own
        objects.add(scanResult.getAllClasses());
        objects.add(scanResult.getAllResources());
        objects.add(scanResult.getPackageInfo());
        objects.add(scanResult.getModuleInfo());
        objects.add(classInfo.getDeclaredFieldInfo());
        objects.add(classInfo.getDeclaredMethodInfo());
        objects.add(classInfo.getAllAnnotationInfo());

        objects.removeIf(java.util.Objects::isNull);
        return objects;
    }

    /** Add a type signature and the type signatures nested within it to the list of objects to sweep. */
    private static void addTypeSignature(final HierarchicalTypeSignature typeSignature,
            final List<Object> objects) {
        if (typeSignature == null) {
            return;
        }
        objects.add(typeSignature);
        if (typeSignature instanceof final ClassRefTypeSignature classRefTypeSignature) {
            for (final TypeArgument typeArgument : classRefTypeSignature.getTypeArguments()) {
                addTypeSignature(typeArgument.getTypeSignature(), objects);
            }
        } else if (typeSignature instanceof final MethodTypeSignature methodTypeSignature) {
            for (final TypeParameter typeParameter : methodTypeSignature.getTypeParameters()) {
                objects.add(typeParameter);
            }
            for (final TypeSignature paramTypeSignature : methodTypeSignature.getParameterTypeSignatures()) {
                addTypeSignature(paramTypeSignature, objects);
            }
        } else if (typeSignature instanceof final ClassTypeSignature classTypeSignature) {
            for (final TypeParameter typeParameter : classTypeSignature.getTypeParameters()) {
                objects.add(typeParameter);
            }
        }
    }

    /** Try to add an element to the given list. Returns true if the list rejected the modification. */
    private static boolean isUnmodifiable(final List<?> list) {
        @SuppressWarnings({ "unchecked", "rawtypes" })
        final List<Object> rawList = (List) list;
        try {
            rawList.add(null);
        } catch (final UnsupportedOperationException e) {
            return true;
        }
        // The add succeeded, so undo it, to avoid corrupting the ScanResult for the rest of the test
        rawList.remove(rawList.size() - 1);
        return false;
    }

    /** Try to add an entry to the given map. Returns true if the map rejected the modification. */
    private static boolean isUnmodifiable(final Map<?, ?> map) {
        @SuppressWarnings({ "unchecked", "rawtypes" })
        final Map<Object, Object> rawMap = (Map) map;
        try {
            rawMap.put(new Object(), null);
        } catch (final UnsupportedOperationException e) {
            return true;
        }
        // The put succeeded, so undo it, to avoid corrupting the ScanResult for the rest of the test
        rawMap.values().remove(null);
        return false;
    }

    /** Every collection returned by a public no-arg accessor should be unmodifiable. */
    @Test
    public void everyReturnedCollectionIsUnmodifiable() {
        final var modifiableCollections = new TreeSet<String>();
        var numCollectionsChecked = 0;
        try (var scanResult = new ClassGraph().enableClasspath()
                .acceptPackages(ClassWithMembers.class.getPackage().getName()).enableAllInfo().scan()) {
            for (final Object object : scanResultObjects(scanResult)) {
                for (final Method method : object.getClass().getMethods()) {
                    final Class<?> returnType = method.getReturnType();
                    if (method.getParameterCount() != 0 || !Modifier.isPublic(method.getModifiers())
                            || !(List.class.isAssignableFrom(returnType)
                                    || Map.class.isAssignableFrom(returnType))) {
                        continue;
                    }
                    final Object returned;
                    try {
                        returned = method.invoke(object);
                    } catch (final IllegalAccessException | InvocationTargetException e) {
                        // Skip accessors that are not valid for this object, e.g. because they throw
                        // IllegalStateException for a class that is not an annotation
                        continue;
                    }
                    if (returned == null) {
                        continue;
                    }
                    final String accessor = object.getClass().getSimpleName() + "#" + method.getName() + "()";
                    numCollectionsChecked++;
                    if (returned instanceof final Map<?, ?> map) {
                        if (!isUnmodifiable(map)) {
                            modifiableCollections.add(accessor);
                        }
                        // Collections nested in a returned map should be unmodifiable too
                        for (final Object value : map.values()) {
                            if (value instanceof final List<?> nestedList && !isUnmodifiable(nestedList)) {
                                modifiableCollections.add(accessor + " value");
                            }
                        }
                    } else if (!isUnmodifiable((List<?>) returned)) {
                        modifiableCollections.add(accessor);
                    }
                }
            }
        }
        // Guard against the test passing vacuously if the reflection above stops finding accessors
        assertThat(numCollectionsChecked).isGreaterThan(80);
        assertThat(modifiableCollections).isEmpty();
    }

    /** Public collection constructors produce completed, unmodifiable info lists. */
    @Test
    public void publicInfoListConstructorsProduceUnmodifiableLists() {
        assertThat(isUnmodifiable(new ClassInfoList(List.of()))).isTrue();
        assertThat(isUnmodifiable(new AnnotationInfoList(List.of()))).isTrue();
        assertThat(isUnmodifiable(new MethodInfoList(List.of()))).isTrue();
        assertThat(isUnmodifiable(new FieldInfoList(List.of()))).isTrue();
        assertThat(isUnmodifiable(new PackageInfoList(List.of()))).isTrue();
        assertThat(isUnmodifiable(new ModuleInfoList(List.of()))).isTrue();
        assertThat(isUnmodifiable(new ResourceList(List.of()))).isTrue();
        assertThat(isUnmodifiable(new AnnotationParameterValueList(List.of()))).isTrue();
    }

    /** A public info-list constructor snapshots the caller's completed collection. */
    @Test
    public void aPublicInfoListConstructorCopiesAndUniquifiesItsInput() {
        try (var scanResult = new ClassGraph().enableClasspath()
                .acceptPackages(ClassWithMembers.class.getPackage().getName()).enableAllInfo().scan()) {
            final var classes = List.copyOf(scanResult.getAllClasses());
            assertThat(classes).hasSizeGreaterThan(1);
            final var source = new ArrayList<ClassInfo>();
            source.add(classes.get(1));
            source.add(classes.get(0));
            source.add(classes.get(1));
            final var classInfoList = new ClassInfoList(source);
            source.clear();

            assertThat(classInfoList).containsExactly(classes.get(0), classes.get(1));
            assertThat(classInfoList.directOnly()).containsExactlyElementsOf(classInfoList);
            assertThat(isUnmodifiable(classInfoList)).isTrue();
        }
    }

    /** The other mutating methods on a returned list should be rejected too, not just {@code add}. */
    @Test
    public void allMutatorsAreRejectedOnReturnedLists() {
        try (var scanResult = new ClassGraph().enableClasspath()
                .acceptPackages(ClassWithMembers.class.getPackage().getName()).enableAllInfo().scan()) {
            final ClassInfoList classInfoList = scanResult.getAllClasses();
            assertThat(classInfoList).hasSizeGreaterThan(1);

            assertThat(throwsUnsupported(() -> classInfoList.sort(null))).isTrue();
            assertThat(throwsUnsupported(() -> classInfoList.removeIf(ci -> true))).isTrue();
            assertThat(throwsUnsupported(() -> classInfoList.replaceAll(ci -> ci))).isTrue();
            assertThat(throwsUnsupported(() -> classInfoList.clear())).isTrue();
            assertThat(throwsUnsupported(() -> classInfoList.set(0, classInfoList.get(0)))).isTrue();
            assertThat(throwsUnsupported(() -> classInfoList.remove(0))).isTrue();
            // Removing by value is a different method from removing by index, and is rejected whether or not the
            // value is in the list
            assertThat(throwsUnsupported(() -> classInfoList.remove(classInfoList.get(0)))).isTrue();
            assertThat(throwsUnsupported(() -> classInfoList.remove(new Object()))).isTrue();
            assertThat(throwsUnsupported(() -> classInfoList.listIterator().add(classInfoList.get(0)))).isTrue();
            assertThat(throwsUnsupported(() -> classInfoList.listIterator(1).set(classInfoList.get(0)))).isTrue();
            assertThat(throwsUnsupported(() -> classInfoList.iterator().remove())).isTrue();
            assertThat(throwsUnsupported(() -> classInfoList.subList(0, 1).clear())).isTrue();
        }
    }

    /**
     * A mutation that would not actually change the contents of a returned list should still be rejected, matching
     * the contract of the unmodifiable views returned by {@link java.util.Collections}.
     */
    @Test
    public void noOpMutatorsAreRejectedOnReturnedLists() {
        try (var scanResult = new ClassGraph().enableClasspath()
                .acceptPackages(ClassWithMembers.class.getPackage().getName()).enableAllInfo().scan()) {
            // An empty returned list: every mutator is a no-op on it, but must still be rejected
            final ClassInfoList emptyList = scanResult.getAllClasses().filter(ci -> false);
            assertThat(emptyList).isEmpty();
            assertThat(throwsUnsupported(emptyList::clear)).isTrue();
            assertThat(throwsUnsupported(() -> emptyList.sort(null))).isTrue();
            assertThat(throwsUnsupported(() -> emptyList.removeIf(ci -> true))).isTrue();
            assertThat(throwsUnsupported(() -> emptyList.replaceAll(ci -> ci))).isTrue();
            assertThat(throwsUnsupported(() -> emptyList.addAll(List.of()))).isTrue();
            assertThat(throwsUnsupported(() -> emptyList.addAll(0, List.of()))).isTrue();
            assertThat(throwsUnsupported(() -> emptyList.removeAll(List.of()))).isTrue();
            assertThat(throwsUnsupported(() -> emptyList.retainAll(List.of()))).isTrue();

            // A non-empty returned list, given collection arguments that would not change it
            final ClassInfoList allClasses = scanResult.getAllClasses();
            assertThat(allClasses).isNotEmpty();
            assertThat(throwsUnsupported(() -> allClasses.addAll(List.of()))).isTrue();
            assertThat(throwsUnsupported(() -> allClasses.removeAll(List.of()))).isTrue();
            assertThat(throwsUnsupported(() -> allClasses.retainAll(allClasses))).isTrue();
        }
    }

    /**
     * Accessors that take a parameter are not reached by the reflective sweep above, so check the ones that build a
     * list here.
     */
    @Test
    public void parameterizedAccessorsReturnUnmodifiableLists() {
        final String classfilePath = ClassWithMembers.class.getName().replace('.', '/') + ".class";
        try (var scanResult = new ClassGraph().enableClasspath()
                .acceptPackages(ClassWithMembers.class.getPackage().getName()).enableAllInfo().scan()) {
            final ClassInfo classInfo = scanResult.getClassInfo(ClassWithMembers.class.getName());
            assertThat(classInfo).isNotNull();

            // Resource accessors
            assertThat(scanResult.getResourcesWithExtension("class")).isNotEmpty();
            assertThat(isUnmodifiable(scanResult.getResourcesWithExtension("class"))).isTrue();
            assertThat(scanResult.getResourcesWithPath(classfilePath)).isNotEmpty();
            assertThat(isUnmodifiable(scanResult.getResourcesWithPath(classfilePath))).isTrue();
            assertThat(scanResult.getResourcesWithPathIgnoringAccept(classfilePath)).isNotEmpty();
            assertThat(isUnmodifiable(scanResult.getResourcesWithPathIgnoringAccept(classfilePath))).isTrue();
            assertThat(scanResult.getResourcesWithLeafName("ClassGraph.class")).isNotEmpty();
            assertThat(isUnmodifiable(scanResult.getResourcesWithLeafName("ClassGraph.class"))).isTrue();
            assertThat(scanResult.getResourcesMatchingWildcard("**/*.class")).isNotEmpty();
            assertThat(isUnmodifiable(scanResult.getResourcesMatchingWildcard("**/*.class"))).isTrue();
            assertThat(isUnmodifiable(scanResult.getAllResources().get(classfilePath))).isTrue();
            assertThat(isUnmodifiable(scanResult.getAllResources().classFilesOnly())).isTrue();

            // Named methods and fields
            assertThat(classInfo.getDeclaredMethodInfo("method")).isNotEmpty();
            assertThat(isUnmodifiable(classInfo.getDeclaredMethodInfo("method"))).isTrue();
            assertThat(isUnmodifiable(classInfo.getMethodInfo("method"))).isTrue();
            assertThat(isUnmodifiable(classInfo.getDeclaredMethodInfo().get("method"))).isTrue();

            // Annotation-filtered accessors
            assertThat(classInfo.getDeclaredMethodInfoWithAnnotation(Deprecated.class.getName())).isNotEmpty();
            assertThat(isUnmodifiable(classInfo.getDeclaredMethodInfoWithAnnotation(Deprecated.class.getName())))
                    .isTrue();
            assertThat(classInfo.getDeclaredFieldInfoWithAnnotation(Deprecated.class.getName())).isNotEmpty();
            assertThat(isUnmodifiable(classInfo.getDeclaredFieldInfoWithAnnotation(Deprecated.class.getName())))
                    .isTrue();
            assertThat(isUnmodifiable(classInfo.getAllAnnotationInfo().getRepeatable(Deprecated.class.getName())))
                    .isTrue();

            // filter()
            assertThat(isUnmodifiable(scanResult.getAllClasses().filter(ci -> true))).isTrue();
            assertThat(isUnmodifiable(scanResult.getAllResources().filter(res -> true))).isTrue();
            assertThat(isUnmodifiable(scanResult.getPackageInfo().filter(pi -> true))).isTrue();
            assertThat(isUnmodifiable(scanResult.getModuleInfo().filter(mi -> true))).isTrue();
            assertThat(isUnmodifiable(classInfo.getDeclaredMethodInfo().filter(mi -> true))).isTrue();
            assertThat(isUnmodifiable(classInfo.getDeclaredFieldInfo().filter(fi -> true))).isTrue();
            assertThat(isUnmodifiable(classInfo.getAllAnnotationInfo().filter(ai -> true))).isTrue();

            // ClassInfoList set operations
            final ClassInfoList allClasses = scanResult.getAllClasses();
            assertThat(isUnmodifiable(allClasses.union(scanResult.getAllStandardClasses()))).isTrue();
            assertThat(isUnmodifiable(allClasses.intersect(scanResult.getAllStandardClasses()))).isTrue();
            assertThat(isUnmodifiable(allClasses.exclude(scanResult.getAllStandardClasses()))).isTrue();
        }
    }

    /** Run the given runnable, and report whether it threw {@link UnsupportedOperationException}. */
    private static boolean throwsUnsupported(final Runnable runnable) {
        try {
            runnable.run();
            return false;
        } catch (final UnsupportedOperationException e) {
            return true;
        }
    }

    /** A class with members and annotations, so that the accessors above have something to return. */
    @SuppressWarnings("unused")
    @Deprecated
    static class ClassWithMembers {
        /** A field. */
        public int field;

        /** A field with a generic type, so that type arguments are present. */
        public Map<String, List<Integer>> genericField;

        /** A field whose type has a suffix, so that suffix type arguments are present. */
        public Map.Entry<String, String> entryField;

        /** An annotated field, so that the annotation-filtered field accessors have something to return. */
        @Deprecated
        public int annotatedField;

        /**
         * A method.
         *
         * @param param
         *            a parameter
         * @return the parameter
         */
        public int method(final int param) {
            return param;
        }

        /**
         * A generic method that throws, so that type parameters and thrown exceptions are present.
         *
         * @param <T>
         *            a type parameter
         * @param param
         *            a parameter
         * @return the parameter
         * @throws IllegalStateException
         *             never
         */
        public <T extends Comparable<T>> T genericMethod(final T param) throws IllegalStateException {
            return param;
        }

        /**
         * An annotated method, so that the annotation-filtered method accessors have something to return.
         *
         * @return zero
         */
        @Deprecated
        public int annotatedMethod() {
            return 0;
        }
    }
}
