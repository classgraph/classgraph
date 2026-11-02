package io.github.classgraph.issues;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

/**
 * Classfiles compiled against different versions of a library can disagree about the access level of a nested class
 * in their {@code InnerClasses} attributes. Classfiles are linked in no fixed order, so each test scans the same two
 * directories in both orders, and the access level must not depend on the order.
 */
public class NestedClassAccessLevelTest {
    /** The nested class. */
    private static final String INNER = "p/Outer$Inner";

    /** The enclosing class. */
    private static final String OUTER = "p/Outer";

    /** Package-private static access flags. */
    private static final int PACKAGE_PRIVATE = Modifier.STATIC;

    /** Public static access flags. */
    private static final int PUBLIC = Modifier.PUBLIC | Modifier.STATIC;

    /** Protected static access flags. */
    private static final int PROTECTED = Modifier.PROTECTED | Modifier.STATIC;

    /**
     * Write a classfile whose {@code InnerClasses} attribute has one entry, naming {@link #INNER} as a nested class
     * of {@link #OUTER}.
     *
     * @param dir
     *            the directory to write the classfile to.
     * @param className
     *            the internal name of the class.
     * @param accessFlags
     *            the access flags of the entry.
     * @throws IOException
     *             if the classfile could not be written.
     */
    private static void writeClassfile(final Path dir, final String className, final int accessFlags)
            throws IOException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(0xCAFEBABE);
            out.writeShort(0);
            out.writeShort(52);
            final String[] classes = { className, "java/lang/Object", INNER, OUTER };
            out.writeShort(1 + 2 * classes.length + 2);
            for (int i = 0; i < classes.length; i++) {
                out.writeByte(1);
                out.writeUTF(classes[i]);
                out.writeByte(7);
                out.writeShort(2 * i + 1);
            }
            final int innerClassesAttributeName = 2 * classes.length + 1;
            out.writeByte(1);
            out.writeUTF("InnerClasses");
            final int innerName = innerClassesAttributeName + 1;
            out.writeByte(1);
            out.writeUTF("Inner");
            // Public class, this class, superclass, then no interfaces, fields or methods
            out.writeShort(0x21);
            out.writeShort(2);
            out.writeShort(4);
            out.writeShort(0);
            out.writeShort(0);
            out.writeShort(0);
            out.writeShort(1);
            out.writeShort(innerClassesAttributeName);
            out.writeInt(2 + 8);
            out.writeShort(1);
            out.writeShort(6);
            out.writeShort(8);
            out.writeShort(innerName);
            out.writeShort(accessFlags);
        }
        final Path file = dir.resolve(className + ".class");
        Files.createDirectories(file.getParent());
        Files.write(file, bytes.toByteArray());
    }

    /**
     * Write the classfile of an anonymous class {@code p/Outer$1}, as a compiler can: public in the classfile's
     * access flags, package-private in its own {@code InnerClasses} entry, with an {@code EnclosingMethod} attribute.
     *
     * @param dir
     *            the directory to write the classfile to.
     * @throws IOException
     *             if the classfile could not be written.
     */
    private static void writeAnonymousClassfile(final Path dir) throws IOException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(0xCAFEBABE);
            out.writeShort(0);
            out.writeShort(52);
            final String[] classes = { "p/Outer$1", "java/lang/Object", OUTER };
            out.writeShort(1 + 2 * classes.length + 2);
            for (int i = 0; i < classes.length; i++) {
                out.writeByte(1);
                out.writeUTF(classes[i]);
                out.writeByte(7);
                out.writeShort(2 * i + 1);
            }
            final int innerClassesAttributeName = 2 * classes.length + 1;
            out.writeByte(1);
            out.writeUTF("InnerClasses");
            out.writeByte(1);
            out.writeUTF("EnclosingMethod");
            // Public class, this class, superclass, then no interfaces, fields or methods
            out.writeShort(0x21);
            out.writeShort(2);
            out.writeShort(4);
            out.writeShort(0);
            out.writeShort(0);
            out.writeShort(0);
            out.writeShort(2);
            out.writeShort(innerClassesAttributeName);
            out.writeInt(2 + 8);
            out.writeShort(1);
            out.writeShort(2);
            out.writeShort(0);
            out.writeShort(0);
            out.writeShort(0);
            out.writeShort(innerClassesAttributeName + 1);
            out.writeInt(4);
            out.writeShort(6);
            out.writeShort(0);
        }
        final Path file = dir.resolve("p/Outer$1.class");
        Files.createDirectories(file.getParent());
        Files.write(file, bytes.toByteArray());
    }

    /**
     * Scan two directories in both orders, on one thread so that classfiles are linked in classpath order.
     *
     * @param first
     *            the first directory.
     * @param second
     *            the second directory.
     * @param expectedAccessLevel
     *            the expected access level modifier bits of the nested class.
     */
    private static void assertAccessLevel(final Path first, final Path second, final int expectedAccessLevel) {
        final int accessLevelBits = Modifier.PUBLIC | Modifier.PROTECTED | Modifier.PRIVATE;
        for (final Path[] dirs : new Path[][] { { first, second }, { second, first } }) {
            try (ScanResult scanResult = new ClassGraph().enableClassInfo().enableExternalClasses()
                    .ignoreClassVisibility().overrideClasspath(dirs[0].toString(), dirs[1].toString()).scan(1)) {
                final int modifiers = scanResult.getClassInfo("p.Outer$Inner").getModifiers();
                assertThat(modifiers & accessLevelBits).isEqualTo(expectedAccessLevel);
                assertThat(Modifier.isStatic(modifiers)).isTrue();
            }
        }
    }

    /**
     * The entry in a scanned class's own classfile wins, whether another entry is wider or narrower.
     *
     * @param tempDir
     *            the directory to write classfiles to.
     * @throws IOException
     *             if a classfile could not be written.
     */
    @Test
    public void ownEntryWins(@TempDir final Path tempDir) throws IOException {
        final Path own = tempDir.resolve("own");
        final Path client = tempDir.resolve("client");
        writeClassfile(own, INNER, PACKAGE_PRIVATE);
        writeClassfile(client, "p/Client", PUBLIC);
        assertAccessLevel(own, client, 0);

        final Path publicOwn = tempDir.resolve("publicOwn");
        final Path narrowClient = tempDir.resolve("narrowClient");
        writeClassfile(publicOwn, INNER, PUBLIC);
        writeClassfile(narrowClient, "p/Client", PACKAGE_PRIVATE);
        assertAccessLevel(publicOwn, narrowClient, Modifier.PUBLIC);
    }

    /**
     * Without a classfile for the nested class, the entry in the enclosing class's classfile wins.
     *
     * @param tempDir
     *            the directory to write classfiles to.
     * @throws IOException
     *             if a classfile could not be written.
     */
    @Test
    public void enclosingClassEntryBeatsOtherEntries(@TempDir final Path tempDir) throws IOException {
        final Path enclosing = tempDir.resolve("enclosing");
        final Path client = tempDir.resolve("client");
        writeClassfile(enclosing, OUTER, PROTECTED);
        writeClassfile(client, "p/Client", PUBLIC);
        assertAccessLevel(enclosing, client, Modifier.PROTECTED);
    }

    /**
     * With only classfiles that refer to the nested class, the widest access level wins.
     *
     * @param tempDir
     *            the directory to write classfiles to.
     * @throws IOException
     *             if a classfile could not be written.
     */
    @Test
    public void widestOtherEntryWins(@TempDir final Path tempDir) throws IOException {
        final Path first = tempDir.resolve("first");
        final Path second = tempDir.resolve("second");
        writeClassfile(first, "p/Client1", PACKAGE_PRIVATE);
        writeClassfile(second, "p/Client2", PUBLIC);
        assertAccessLevel(first, second, Modifier.PUBLIC);
    }

    /**
     * The access flags of an anonymous class's classfile are not its access level: its own {@code InnerClasses}
     * entry is.
     *
     * @param tempDir
     *            the directory to write classfiles to.
     * @throws IOException
     *             if a classfile could not be written.
     */
    @Test
    public void anonymousClassUsesOwnEntryNotClassfileFlags(@TempDir final Path tempDir) throws IOException {
        writeAnonymousClassfile(tempDir);
        try (ScanResult scanResult = new ClassGraph().enableClassInfo().enableExternalClasses()
                .ignoreClassVisibility().overrideClasspath(tempDir.toString()).scan(1)) {
            assertThat(scanResult.getClassInfo("p.Outer$1").isPublic()).isFalse();
        }
    }
}
