package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * {@link ClassInfo#getOrCreateClassInfo(String, Map)} tries to be resilient to being passed a class descriptor
 * ("Ljava/lang/String;") rather than a class name, but it stripped the descriptor by keeping only its last
 * character, rather than by removing the leading 'L' and the trailing ';'.
 */
public class ClassInfoDescriptorNameTest {
    /** A class descriptor is converted to a class name. */
    @Test
    public void classDescriptorIsConvertedToClassName() {
        final Map<String, ClassInfo> classNameToClassInfo = new HashMap<>();
        assertThat(ClassInfo.getOrCreateClassInfo("Ljava/lang/String;", classNameToClassInfo).getName())
                .isEqualTo("java.lang.String");
    }

    /** An array class descriptor is converted to a class name. */
    @Test
    public void arrayClassDescriptorIsConvertedToClassName() {
        final Map<String, ClassInfo> classNameToClassInfo = new HashMap<>();
        assertThat(ClassInfo.getOrCreateClassInfo("[Ljava/lang/String;", classNameToClassInfo).getName())
                .isEqualTo("java.lang.String[]");
    }

    /** A class reached by descriptor and by name is one {@link ClassInfo}, stored under its class name. */
    @Test
    public void aDescriptorAndAClassNameNameTheSameClass() {
        final Map<String, ClassInfo> classNameToClassInfo = new HashMap<>();
        final ClassInfo fromDescriptor = ClassInfo.getOrCreateClassInfo("Ljava/lang/String;",
                classNameToClassInfo);
        final ClassInfo fromName = ClassInfo.getOrCreateClassInfo("java.lang.String", classNameToClassInfo);
        assertThat(fromName).isSameAs(fromDescriptor);
        assertThat(classNameToClassInfo).containsOnlyKeys("java.lang.String");
    }

    /** An array class reached by descriptor and by name is one {@link ClassInfo}, stored under its class name. */
    @Test
    public void anArrayDescriptorAndAnArrayClassNameNameTheSameClass() {
        final Map<String, ClassInfo> classNameToClassInfo = new HashMap<>();
        final ClassInfo fromDescriptor = ClassInfo.getOrCreateClassInfo("[Ljava/lang/String;",
                classNameToClassInfo);
        final ClassInfo fromName = ClassInfo.getOrCreateClassInfo("java.lang.String[]", classNameToClassInfo);
        assertThat(fromName).isSameAs(fromDescriptor);
        assertThat(classNameToClassInfo).containsKey("java.lang.String[]");
    }
}
