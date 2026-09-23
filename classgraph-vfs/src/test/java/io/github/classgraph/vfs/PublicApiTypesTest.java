package io.github.classgraph.vfs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/** The public API of the package names only types that a caller outside the package can use. */
public class PublicApiTypesTest {
    /**
     * Returns whether code outside the package can name a type.
     *
     * @param type
     *            the type.
     * @return true if the type, and every class it is nested in, is public.
     */
    private static boolean isAccessible(final Class<?> type) {
        var t = type;
        while (t.isArray()) {
            t = t.getComponentType();
        }
        if (t.isPrimitive()) {
            return true;
        }
        for (var c = t; c != null; c = c.getDeclaringClass()) {
            if (!Modifier.isPublic(c.getModifiers())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Every public or protected method, constructor and field of a public type of the package returns, takes and
     * holds public types only. A method that returns a package-private type compiles, but a caller outside the
     * package cannot call anything on what it returns.
     */
    @Test
    public void publicMembersNameOnlyPublicTypes() throws IOException, URISyntaxException, ClassNotFoundException {
        final var packageDir = Path.of(Vfs.class.getResource("Vfs.class").toURI()).getParent();
        final List<String> violations = new ArrayList<>();
        try (Stream<Path> classFiles = Files.list(packageDir)) {
            for (final var classFile : (Iterable<Path>) classFiles::iterator) {
                final var fileName = classFile.getFileName().toString();
                if (!fileName.endsWith(".class") || fileName.equals("package-info.class")) {
                    continue;
                }
                final var type = Class
                        .forName(Vfs.class.getPackageName() + "." + fileName.substring(0, fileName.length() - 6));
                if (!isAccessible(type)) {
                    continue;
                }
                final List<Member> members = new ArrayList<>();
                members.addAll(List.of(type.getDeclaredMethods()));
                members.addAll(List.of(type.getDeclaredConstructors()));
                members.addAll(List.of(type.getDeclaredFields()));
                for (final var member : members) {
                    final var modifiers = member.getModifiers();
                    if (member.isSynthetic() || !Modifier.isPublic(modifiers) && !Modifier.isProtected(modifiers)) {
                        continue;
                    }
                    final List<Class<?>> namedTypes = new ArrayList<>();
                    if (member instanceof final Method method) {
                        if (method.isBridge()) {
                            continue;
                        }
                        namedTypes.add(method.getReturnType());
                    } else if (member instanceof final Field field) {
                        namedTypes.add(field.getType());
                    }
                    if (member instanceof final Executable executable) {
                        namedTypes.addAll(List.of(executable.getParameterTypes()));
                    }
                    for (final var namedType : namedTypes) {
                        if (!isAccessible(namedType)) {
                            violations.add(type.getSimpleName() + "." + member.getName() + " names "
                                    + namedType.getName());
                        }
                    }
                }
            }
        }
        assertThat(violations).isEmpty();
    }
}
