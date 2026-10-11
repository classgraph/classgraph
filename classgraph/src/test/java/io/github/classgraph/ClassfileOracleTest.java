package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.classfile.AnnotationValue;
import java.lang.classfile.AttributedElement;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.attribute.RuntimeInvisibleParameterAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeVisibleParameterAnnotationsAttribute;
import java.lang.classfile.constantpool.Utf8Entry;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

/**
 * Checks ClassGraph's classfile parser against a second, independent parser, the JDK's {@code java.lang.classfile}
 * API. The scan covers ClassGraph's own classes and test classes, and the classes of the packages {@code java.lang}
 * and {@code java.lang.invoke} of the running JDK. For each scanned class, the information ClassGraph reports is
 * compared with what {@code java.lang.classfile} reads from the same bytes.
 *
 * <p>
 * Because the scan reads the running JDK's own classes, this test can fail after a JDK upgrade with no change in
 * ClassGraph. Such a failure may mean that the new JDK uses a classfile feature ClassGraph does not handle yet (for
 * example, a new meaning for an access flag bit), not that the parser regressed.
 */
public class ClassfileOracleTest {
    /** The access level modifier bits that an {@code InnerClasses} entry replaces. */
    private static final int ACCESS_LEVEL = Modifier.PUBLIC | Modifier.PRIVATE | Modifier.PROTECTED;

    /** The {@code ACC_SUPER} class flag, which {@link ClassInfo#getModifiers()} leaves out. */
    private static final int ACC_SUPER = 0x20;

    /**
     * The access flags that JVMS 4.1 defines for a class: {@code ACC_PUBLIC}, {@code ACC_FINAL}, {@code ACC_SUPER},
     * {@code ACC_INTERFACE}, {@code ACC_ABSTRACT}, {@code ACC_SYNTHETIC}, {@code ACC_ANNOTATION}, {@code ACC_ENUM}
     * and {@code ACC_MODULE}.
     */
    private static final int CLASS_FLAGS = 0xF631;

    /**
     * The access flags that JVMS 4.7.6 defines for an {@code InnerClasses} entry: {@code ACC_PUBLIC},
     * {@code ACC_PRIVATE}, {@code ACC_PROTECTED}, {@code ACC_STATIC}, {@code ACC_FINAL}, {@code ACC_INTERFACE},
     * {@code ACC_ABSTRACT}, {@code ACC_SYNTHETIC}, {@code ACC_ANNOTATION} and {@code ACC_ENUM}.
     */
    private static final int INNER_CLASS_FLAGS = 0x761F;

    /** The differences found, one line each. */
    private final List<String> mismatches = new ArrayList<>();

    /**
     * Record a difference if the two values are not equal.
     *
     * @param what
     *            what was compared
     * @param classGraphValue
     *            the value ClassGraph reports
     * @param classfileApiValue
     *            the value {@code java.lang.classfile} reads
     */
    private void check(final String what, final Object classGraphValue, final Object classfileApiValue) {
        if (!Objects.equals(classGraphValue, classfileApiValue)) {
            mismatches.add(what + ": ClassGraph=" + classGraphValue + " java.lang.classfile=" + classfileApiValue);
        }
    }

    /**
     * Convert an internal name, such as {@code java/lang/Object}, to a class name.
     *
     * @param internalName
     *            the internal name
     * @return the class name
     */
    private static String toClassName(final String internalName) {
        return internalName.replace('/', '.');
    }

    /**
     * Get the class name of an annotation.
     *
     * @param annotation
     *            the annotation
     * @return the class name of its type
     */
    private static String annotationName(final java.lang.classfile.Annotation annotation) {
        final var descriptor = annotation.className().stringValue();
        return toClassName(descriptor.substring(1, descriptor.length() - 1));
    }

    /**
     * Get the names of annotations, adding the annotations that each container of a
     * {@link java.lang.annotation.Repeatable} annotation contains, as ClassGraph does. The container itself is
     * kept.
     *
     * @param annotations
     *            the annotations
     * @param containerNames
     *            the names of the container annotations
     * @return the annotation names
     */
    private static Set<String> annotationNames(final List<java.lang.classfile.Annotation> annotations,
            final Set<String> containerNames) {
        final Set<String> names = new TreeSet<>();
        for (final var annotation : annotations) {
            final var name = annotationName(annotation);
            names.add(name);
            if (!containerNames.contains(name)) {
                continue;
            }
            for (final var element : annotation.elements()) {
                if (element.name().equalsString("value")
                        && element.value() instanceof final AnnotationValue.OfArray array) {
                    for (final var value : array.values()) {
                        if (value instanceof final AnnotationValue.OfAnnotation nested) {
                            names.add(annotationName(nested.annotation()));
                        }
                    }
                }
            }
        }
        return names;
    }

    /**
     * Get the names of the visible and invisible annotations of a class, field or method.
     *
     * @param element
     *            the class, field or method
     * @param containerNames
     *            the names of the container annotations
     * @return the annotation names
     */
    private static Set<String> annotationNames(final AttributedElement element, final Set<String> containerNames) {
        final List<java.lang.classfile.Annotation> annotations = new ArrayList<>();
        element.findAttribute(Attributes.runtimeVisibleAnnotations())
                .ifPresent(attr -> annotations.addAll(attr.annotations()));
        element.findAttribute(Attributes.runtimeInvisibleAnnotations())
                .ifPresent(attr -> annotations.addAll(attr.annotations()));
        return annotationNames(annotations, containerNames);
    }

    /**
     * Get the names of the annotations ClassGraph reports.
     *
     * @param annotationInfoList
     *            the annotations
     * @return the annotation names
     */
    private static Set<String> annotationNames(final AnnotationInfoList annotationInfoList) {
        return new TreeSet<>(annotationInfoList.directOnly().getNames());
    }

    /**
     * Get the signature of a class, field or method.
     *
     * @param element
     *            the class, field or method
     * @return the signature, or null if there is none
     */
    private static String signature(final AttributedElement element) {
        return element.findAttribute(Attributes.signature()).map(attr -> attr.signature().stringValue())
                .orElse(null);
    }

    /**
     * Convert the boxed type ClassGraph uses for a constant to the one {@code java.lang.classfile} uses.
     *
     * @param value
     *            the constant value from ClassGraph
     * @return the value as {@code java.lang.classfile} reports it
     */
    private static Object normalizeConstant(final Object value) {
        return switch (value) {
        case final Character c -> (int) c;
        case final Boolean b -> b ? 1 : 0;
        case final Byte b -> (int) b;
        case final Short s -> (int) s;
        case null, default -> value;
        };
    }

    /**
     * Compare every scanned class with {@code java.lang.classfile}.
     */
    @Test
    public void classGraphAgreesWithJavaLangClassfile() {
        try (var scanResult = new ClassGraph().enableClasspath().enableSystemModules()
                .acceptPackages(ClassGraph.class.getPackageName())
                .acceptPackagesNonRecursive("java.lang", "java.lang.invoke").enableClassInfo().enableFieldInfo()
                .enableMethodInfo().enableAnnotationInfo().enableStaticFinalFieldConstantInitializerValues()
                .ignoreClassVisibility().ignoreFieldVisibility().ignoreMethodVisibility().scan()) {
            // Parse each scanned classfile once, and collect the flags of each class's InnerClasses entry in its
            // own classfile and the names of the @Repeatable container annotations
            final Map<ClassInfo, ClassModel> models = new HashMap<>();
            final Map<String, Integer> ownInnerFlags = new HashMap<>();
            final Set<String> containerNames = new HashSet<>();
            for (final var classInfo : scanResult.getAllClasses()) {
                if (classInfo.isExternalClass()) {
                    continue;
                }
                final var resource = classInfo.getResource();
                assertThat(resource).as(classInfo.getName()).isNotNull();
                final ClassModel model;
                try {
                    model = ClassFile.of().parse(resource.load());
                } catch (final Exception e) {
                    throw new AssertionError("java.lang.classfile could not parse " + classInfo.getName(), e);
                }
                models.put(classInfo, model);
                final var className = toClassName(model.thisClass().asInternalName());
                model.findAttribute(Attributes.innerClasses())
                        .ifPresent(attr -> attr.classes().forEach(innerClass -> {
                            if (toClassName(innerClass.innerClass().asInternalName()).equals(className)) {
                                ownInnerFlags.put(className, innerClass.flagsMask());
                            }
                        }));
                model.findAttribute(Attributes.runtimeVisibleAnnotations()).ifPresent(attr -> {
                    for (final var annotation : attr.annotations()) {
                        if (annotationName(annotation).equals("java.lang.annotation.Repeatable")) {
                            for (final var element : annotation.elements()) {
                                if (element.value() instanceof final AnnotationValue.OfClass container) {
                                    final var descriptor = container.className().stringValue();
                                    containerNames
                                            .add(toClassName(descriptor.substring(1, descriptor.length() - 1)));
                                }
                            }
                        }
                    }
                });
            }
            // Check that the scan covered both the JDK's classes and ClassGraph's own
            assertThat(scanResult.getClassInfo("java.lang.invoke.MethodHandles$Lookup")).isNotNull();
            assertThat(scanResult.getClassInfo(ClassfileOracleTest.class.getName())).isNotNull();

            for (final var entry : models.entrySet()) {
                compareClass(entry.getKey(), entry.getValue(), ownInnerFlags, containerNames);
            }
        }
        assertThat(mismatches).isEmpty();
    }

    /**
     * Compare one class with {@code java.lang.classfile}.
     *
     * @param classInfo
     *            the class as ClassGraph reports it
     * @param model
     *            the class as {@code java.lang.classfile} reads it
     * @param ownInnerFlags
     *            the flags of the InnerClasses entry for each class in its own classfile
     * @param containerNames
     *            the names of the container annotations
     */
    private void compareClass(final ClassInfo classInfo, final ClassModel model,
            final Map<String, Integer> ownInnerFlags, final Set<String> containerNames) {
        final var cn = classInfo.getName();
        check(cn + " name", cn, toClassName(model.thisClass().asInternalName()));
        check(cn + " version", classInfo.getClassfileMajorVersion() + "." + classInfo.getClassfileMinorVersion(),
                model.majorVersion() + "." + model.minorVersion());

        // The modifiers of a class come only from its own classfile: the access level and ACC_STATIC from its
        // own InnerClasses entry if it has one, otherwise its access flags. The JVM ignores the bits that JVMS 4.1
        // does not define for a class (the jlink-generated java.lang.invoke.*$Holder classes set bit 0x0002), and
        // the bits that JVMS 4.7.6 does not define for an InnerClasses entry.
        final var classFlags = model.flags().flagsMask() & CLASS_FLAGS;
        final var ownInner = ownInnerFlags.get(cn);
        final var expectedModifiers = (ownInner == null ? classFlags
                : (classFlags & ~ACCESS_LEVEL) | (ownInner & INNER_CLASS_FLAGS)) & ~ACC_SUPER;
        check(cn + " modifiers", Integer.toHexString(classInfo.getModifiers()),
                Integer.toHexString(expectedModifiers));

        final var typeDescriptor = classInfo.getTypeDescriptor();
        final var superclassSignature = typeDescriptor.getSuperclassSignature();
        final var superclass = model.superclass().map(c -> toClassName(c.asInternalName())).orElse(null);
        // ClassGraph reports no superclass for an interface, whose classfile names java.lang.Object
        check(cn + " superclass",
                superclassSignature == null ? classInfo.isInterfaceOrAnnotation() ? superclass : null
                        : superclassSignature.getFullyQualifiedClassName(),
                superclass);
        check(cn + " interfaces",
                typeDescriptor.getSuperinterfaceSignatures().stream()
                        .map(ClassRefTypeSignature::getFullyQualifiedClassName).toList(),
                model.interfaces().stream().map(c -> toClassName(c.asInternalName())).toList());
        check(cn + " signature", classInfo.getTypeSignatureString(), signature(model));
        check(cn + " isRecord", classInfo.isRecord(), model.findAttribute(Attributes.record()).isPresent());
        check(cn + " source file", classInfo.getSourceFile(), model.findAttribute(Attributes.sourceFile())
                .map(attr -> attr.sourceFile().stringValue()).orElse(null));
        check(cn + " annotations", annotationNames(classInfo.getAllAnnotationInfo()),
                annotationNames(model, containerNames));

        // Fields
        final Map<String, FieldInfo> fieldInfos = new HashMap<>();
        for (final var fieldInfo : classInfo.getDeclaredFieldInfo()) {
            fieldInfos.put(fieldInfo.getName(), fieldInfo);
        }
        check(cn + " field count", fieldInfos.size(), model.fields().size());
        for (final var field : model.fields()) {
            final var fn = cn + "." + field.fieldName().stringValue();
            final var fieldInfo = fieldInfos.get(field.fieldName().stringValue());
            if (fieldInfo == null) {
                mismatches.add(fn + ": field missing from ClassGraph");
                continue;
            }
            final var flags = field.flags().flagsMask();
            check(fn + " modifiers", fieldInfo.getModifiers(), flags);
            check(fn + " descriptor", fieldInfo.getTypeDescriptorString(), field.fieldType().stringValue());
            check(fn + " signature", fieldInfo.getTypeSignatureString(), signature(field));
            if ((flags & (Modifier.STATIC | Modifier.FINAL)) == (Modifier.STATIC | Modifier.FINAL)) {
                check(fn + " constant", normalizeConstant(fieldInfo.getConstantInitializerValue()),
                        field.findAttribute(Attributes.constantValue())
                                .map(attr -> (Object) attr.constant().constantValue()).orElse(null));
            }
            check(fn + " annotations", annotationNames(fieldInfo.getAllAnnotationInfo()),
                    annotationNames(field, containerNames));
        }

        // Methods and constructors (a static initializer is found only by its name)
        final Map<String, MethodInfo> methodInfos = new HashMap<>();
        for (final var methodInfo : classInfo.getDeclaredMethodAndConstructorInfo()) {
            methodInfos.put(methodInfo.getName() + methodInfo.getTypeDescriptorString(), methodInfo);
        }
        for (final var methodInfo : classInfo.getDeclaredMethodInfo("<clinit>")) {
            methodInfos.put(methodInfo.getName() + methodInfo.getTypeDescriptorString(), methodInfo);
        }
        check(cn + " method count", methodInfos.size(), model.methods().size());
        for (final var method : model.methods()) {
            final var key = method.methodName().stringValue() + method.methodType().stringValue();
            final var mn = cn + "." + key;
            final var methodInfo = methodInfos.get(key);
            if (methodInfo == null) {
                mismatches.add(mn + ": method missing from ClassGraph");
                continue;
            }
            check(mn + " modifiers", methodInfo.getModifiers(), method.flags().flagsMask());
            check(mn + " signature", methodInfo.getTypeSignatureString(), signature(method));
            check(mn + " throws", new TreeSet<>(methodInfo.getThrownExceptionNames()),
                    new TreeSet<>(method.findAttribute(Attributes.exceptions()).map(
                            attr -> attr.exceptions().stream().map(c -> toClassName(c.asInternalName())).toList())
                            .orElse(List.of())));
            check(mn + " annotations", annotationNames(methodInfo.getAllAnnotationInfo()),
                    annotationNames(method, containerNames));

            // Parameter names and modifiers, where MethodParameters names every parameter in the descriptor
            final var parameterInfos = methodInfo.getParameterInfo();
            method.findAttribute(Attributes.methodParameters()).ifPresent(attr -> {
                final var parameters = attr.parameters();
                if (parameters.size() == parameterInfos.size()) {
                    for (var i = 0; i < parameters.size(); i++) {
                        check(mn + " parameter " + i + " name", parameterInfos.get(i).getName(),
                                parameters.get(i).name().map(Utf8Entry::stringValue).orElse(null));
                        check(mn + " parameter " + i + " modifiers", parameterInfos.get(i).getModifiers(),
                                parameters.get(i).flagsMask());
                    }
                }
            });

            // Parameter annotations, as the union over all parameters, since javac may write fewer entries
            // than the descriptor has parameters
            final List<java.lang.classfile.Annotation> parameterAnnotations = new ArrayList<>();
            method.findAttribute(Attributes.runtimeVisibleParameterAnnotations())
                    .map(RuntimeVisibleParameterAnnotationsAttribute::parameterAnnotations)
                    .ifPresent(lists -> lists.forEach(parameterAnnotations::addAll));
            method.findAttribute(Attributes.runtimeInvisibleParameterAnnotations())
                    .map(RuntimeInvisibleParameterAnnotationsAttribute::parameterAnnotations)
                    .ifPresent(lists -> lists.forEach(parameterAnnotations::addAll));
            final Set<String> classGraphParameterAnnotations = new TreeSet<>();
            for (final var parameterInfo : parameterInfos) {
                classGraphParameterAnnotations.addAll(annotationNames(parameterInfo.getAllAnnotationInfo()));
            }
            check(mn + " parameter annotations", classGraphParameterAnnotations,
                    annotationNames(parameterAnnotations, containerNames));

            // Body and line numbers
            final var code = method.findAttribute(Attributes.code());
            check(mn + " hasBody", methodInfo.hasBody(), code.isPresent());
            var minLine = Integer.MAX_VALUE;
            var maxLine = 0;
            if (code.isPresent()) {
                for (final var table : code.get().findAttributes(Attributes.lineNumberTable())) {
                    for (final var line : table.lineNumbers()) {
                        minLine = Math.min(minLine, line.lineNumber());
                        maxLine = Math.max(maxLine, line.lineNumber());
                    }
                }
            }
            check(mn + " line numbers", methodInfo.getMinLineNum() + "-" + methodInfo.getMaxLineNum(),
                    (minLine == Integer.MAX_VALUE ? 0 : minLine) + "-" + maxLine);
        }
    }
}
