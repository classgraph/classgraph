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

/** The public API of the package: which types it names, and how those types are declared. */
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

    /**
     * {@link VfsRoot} and {@link VfsEntry} are both sealed, each over the three kinds of root or entry a
     * {@link Vfs} opens, so the API itself states that a root or entry is always one of those three.
     */
    @Test
    public void vfsRootAndVfsEntryAreSealedOverTheirThreeKinds() {
        assertThat(VfsRoot.class.isSealed()).as("VfsRoot is sealed").isTrue();
        assertThat(VfsRoot.class.getPermittedSubclasses()).containsExactlyInAnyOrder(ArchiveRoot.class,
                DirRoot.class, ModuleRoot.class);
        assertThat(VfsEntry.class.isSealed()).as("VfsEntry is sealed").isTrue();
        assertThat(VfsEntry.class.getPermittedSubclasses()).containsExactlyInAnyOrder(ArchiveEntry.class,
                DirEntry.class, ModuleEntry.class);
    }

    /**
     * The container root of a jarfile's root is the root of the whole jarfile, so {@link ArchiveRoot} declares it
     * as an {@link ArchiveRoot}, and a caller needs no cast to use it as one.
     */
    @Test
    public void anArchiveRootDeclaresItsContainerRootAsAnArchiveRoot() throws NoSuchMethodException {
        assertThat(ArchiveRoot.class.getMethod("getContainerRoot").getReturnType()).isEqualTo(ArchiveRoot.class);
    }
}
