package io.github.lukehutch.fastclasspathscanner.scanner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.classfileparser.ClassInfo;
import io.github.lukehutch.fastclasspathscanner.utils.Join;
import io.github.lukehutch.fastclasspathscanner.utils.ThreadLog;

/**
 * Class information that has been directly read from the binary classfile, before it is cross-linked with other
 * classes. (The cross-linking is done in a separate step to avoid the complexity of dealing with race conditions.)
 */
public class ClassInfoUnlinked {
    public String className;
    public boolean isInterface;
    public boolean isAnnotation;
    // Superclass (can be null if no superclass, or if superclass is blacklisted)
    public String superclassName;
    public List<String> implementedInterfaces;
    public List<String> annotations;
    public Set<String> fieldTypes;
    public Map<String, Object> staticFinalFieldValues;
    private ConcurrentHashMap<String, String> stringInternMap;

    /** End of queue marker used for worker threads */
    public static final ClassInfoUnlinked END_OF_QUEUE = new ClassInfoUnlinked();

    private ClassInfoUnlinked() {
    }

    private String intern(final String string) {
        final String oldValue = stringInternMap.putIfAbsent(string, string);
        return oldValue == null ? string : oldValue;
    }

    public ClassInfoUnlinked(final String className, final boolean isInterface, final boolean isAnnotation,
            final ConcurrentHashMap<String, String> stringInternMap) {
        this.stringInternMap = stringInternMap;
        this.className = intern(className);
        this.isInterface = isInterface;
        this.isAnnotation = isAnnotation;
    }

    public void addSuperclass(final String superclassName) {
        this.superclassName = intern(superclassName);
    }

    public void addImplementedInterface(final String interfaceName) {
        if (implementedInterfaces == null) {
            implementedInterfaces = new ArrayList<>();
        }
        implementedInterfaces.add(intern(interfaceName));
    }

    public void addAnnotation(final String annotationName) {
        if (annotations == null) {
            annotations = new ArrayList<>();
        }
        annotations.add(intern(annotationName));
    }

    public void addFieldType(final String fieldTypeName) {
        if (fieldTypes == null) {
            fieldTypes = new HashSet<>();
        }
        fieldTypes.add(intern(fieldTypeName));
    }

    public void addFieldConstantValue(final String fieldName, final Object staticFinalFieldValue) {
        if (staticFinalFieldValues == null) {
            staticFinalFieldValues = new HashMap<>();
        }
        staticFinalFieldValues.put(intern(fieldName), staticFinalFieldValue);
    }

    public void link(final Map<String, ClassInfo> classNameToClassInfo) {
        final ClassInfo classInfo = ClassInfo.addScannedClass(className, isInterface, isAnnotation,
                classNameToClassInfo);
        if (superclassName != null) {
            classInfo.addSuperclass(superclassName, classNameToClassInfo);
        }
        if (implementedInterfaces != null) {
            for (final String interfaceName : implementedInterfaces) {
                classInfo.addImplementedInterface(interfaceName, classNameToClassInfo);
            }
        }
        if (annotations != null) {
            for (final String annotationName : annotations) {
                classInfo.addAnnotation(annotationName, classNameToClassInfo);
            }
        }
        if (fieldTypes != null) {
            for (final String fieldTypeName : fieldTypes) {
                classInfo.addFieldType(fieldTypeName, classNameToClassInfo);
            }
        }
        if (staticFinalFieldValues != null) {
            for (final Entry<String, Object> ent : staticFinalFieldValues.entrySet()) {
                classInfo.addFieldConstantValue(ent.getKey(), ent.getValue());
            }
        }
    }

    public void logClassInfo(final ThreadLog log) {
        if (FastClasspathScanner.verbose) {
            log.log(2, "Found " + (isAnnotation ? "annotation class" : isInterface ? "interface class" : "class")
                    + " " + className);
            if (superclassName != null && !"java.lang.Object".equals(superclassName)) {
                log.log(3,
                        "Super" + (isInterface && !isAnnotation ? "interface" : "class") + ": " + superclassName);
            }
            if (implementedInterfaces != null) {
                log.log(3, "Interfaces: " + Join.join(", ", implementedInterfaces));
            }
            if (annotations != null) {
                log.log(3, "Annotations: " + Join.join(", ", annotations));
            }
            if (fieldTypes != null) {
                log.log(3, "Field types: " + Join.join(", ", fieldTypes));
            }
            if (staticFinalFieldValues != null) {
                final List<String> fieldInitializers = new ArrayList<>();
                for (final Entry<String, Object> ent : staticFinalFieldValues.entrySet()) {
                    fieldInitializers.add(ent.getKey() + " = " + ent.getValue());
                }
                log.log(3, "Static final field values: " + Join.join(", ", fieldInitializers));
            }
        }
    }
}