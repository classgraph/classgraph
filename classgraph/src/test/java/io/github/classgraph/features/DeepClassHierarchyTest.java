package io.github.classgraph.features;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;

/**
 * The queries for inherited fields and methods walk the whole class hierarchy above a class. A hierarchy thousands
 * of levels deep, which a crafted jar can hold, must not make them overflow the stack.
 */
public class DeepClassHierarchyTest {
    /** The number of levels of the class hierarchy. */
    private static final int DEPTH = 3000;

    /**
     * The stack size of the thread that queries the hierarchy, small enough that recursing per level overflows it.
     */
    private static final long STACK_SIZE = 128 * 1024;

    /**
     * A deep chain with an override at every level still selects the most specific declaration, without computing a
     * transitive closure for every interface.
     *
     * @param tempDir
     *            the fixture directory.
     * @throws Exception
     *             if fixture creation or lookup fails.
     */
    @Test
    public void deepInterfaceOverridesChooseTheMostSpecificDeclaration(@TempDir final File tempDir)
            throws Exception {
        for (var i = 0; i < DEPTH; i++) {
            Files.write(new File(tempDir, "I" + i + ".class").toPath(), classfileBytes("I" + i, "java/lang/Object",
                    i == DEPTH - 1 ? null : "I" + (i + 1), /* isInterface = */ true, /* declareMembers = */ true));
        }
        Files.write(new File(tempDir, "Impl.class").toPath(),
                classfileBytes("Impl", "java/lang/Object", "I0", /* isInterface = */ false, false));
        try (var scanResult = new ClassGraph().enableClassInfo().enableMethodInfo().enableClasspathEntries(tempDir)
                .scan()) {
            final var classInfo = scanResult.getClassInfo("Impl");
            assertThat(classInfo).isNotNull();
            final var methods = callWithSmallStack(() -> classInfo.getMethodInfo("m"));
            assertThat(methods).hasSize(1);
            assertThat(methods.get(0).getClassInfo().getName()).isEqualTo("I0");
            assertThat(classInfo.getMethodInfo("m")).containsExactlyElementsOf(methods);
        }
    }

    /**
     * Generate an abstract class or an interface that extends the given superclass and implements at most one
     * interface, and that optionally declares a field {@code int f} and an abstract method {@code void m()}.
     *
     * @param className
     *            the class name
     * @param superclassName
     *            the superclass name
     * @param interfaceName
     *            the name of the implemented or extended interface, or null for none
     * @param isInterface
     *            whether to generate an interface
     * @param declareMembers
     *            whether to declare the field and the method
     * @return the bytes of the classfile.
     * @throws IOException
     *             if the classfile could not be written.
     */
    private static byte[] classfileBytes(final String className, final String superclassName,
            final String interfaceName, final boolean isInterface, final boolean declareMembers)
            throws IOException {
        final var byteArrayOutputStream = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(byteArrayOutputStream)) {
            out.writeInt(0xCAFEBABE);
            out.writeShort(0); // Minor version
            out.writeShort(52); // Major version (Java 8)

            out.writeShort(11); // Constant pool count (one greater than the number of entries)
            // #1
            out.writeByte(1);
            out.writeUTF(className);
            // #2
            out.writeByte(7); // class ref to #1 (this_class)
            out.writeShort(1);
            // #3
            out.writeByte(1);
            out.writeUTF(superclassName);
            // #4
            out.writeByte(7); // class ref to #3 (super_class)
            out.writeShort(3);
            // #5
            out.writeByte(1);
            out.writeUTF(interfaceName == null ? "unused" : interfaceName);
            // #6
            out.writeByte(7); // class ref to #5 (interface)
            out.writeShort(5);
            // #7
            out.writeByte(1);
            out.writeUTF("f");
            // #8
            out.writeByte(1);
            out.writeUTF("I");
            // #9
            out.writeByte(1);
            out.writeUTF("m");
            // #10
            out.writeByte(1);
            out.writeUTF("()V");

            // ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT, or ACC_PUBLIC | ACC_SUPER | ACC_ABSTRACT
            out.writeShort(isInterface ? 0x0601 : 0x0421);
            out.writeShort(2); // this_class
            out.writeShort(4); // super_class
            if (interfaceName == null) {
                out.writeShort(0); // Number of interfaces
            } else {
                out.writeShort(1); // Number of interfaces
                out.writeShort(6);
            }
            if (declareMembers) {
                out.writeShort(1); // Number of fields
                out.writeShort(isInterface ? 0x0019 : 0x0001); // public (static final, in an interface)
                out.writeShort(7); // "f"
                out.writeShort(8); // "I"
                out.writeShort(0); // Number of attributes
                out.writeShort(1); // Number of methods
                out.writeShort(0x0401); // ACC_PUBLIC | ACC_ABSTRACT
                out.writeShort(9); // "m"
                out.writeShort(10); // "()V"
                out.writeShort(0); // Number of attributes
            } else {
                out.writeShort(0); // Number of fields
                out.writeShort(0); // Number of methods
            }
            out.writeShort(0); // Number of attributes
        }
        return byteArrayOutputStream.toByteArray();
    }

    /**
     * Run a task in a new thread with a stack of {@link #STACK_SIZE} bytes.
     *
     * @param <T>
     *            the result type
     * @param task
     *            the task
     * @return the result of the task.
     * @throws Exception
     *             if the task threw an exception or an error.
     */
    private static <T> T callWithSmallStack(final Callable<T> task) throws Exception {
        final var futureTask = new FutureTask<>(task);
        final var thread = new Thread(null, futureTask, "small-stack", STACK_SIZE);
        thread.start();
        thread.join();
        return futureTask.get();
    }

    /**
     * Check that the fields and methods declared at the top of a deep hierarchy are found from its bottom.
     *
     * @param classInfo
     *            the class at the bottom of the hierarchy
     * @throws Exception
     *             if the queries threw an exception or an error.
     */
    private static void assertMembersAreInherited(final ClassInfo classInfo) throws Exception {
        assertThat(callWithSmallStack(() -> classInfo.hasMethod("m"))).isTrue();
        assertThat(callWithSmallStack(() -> classInfo.getMethodInfo("m").size())).isEqualTo(1);
        assertThat(callWithSmallStack(() -> classInfo.hasField("f"))).isTrue();
        assertThat(callWithSmallStack(() -> classInfo.getFieldInfo("f"))).isNotNull();
    }

    /**
     * A class at the bottom of a chain of {@link #DEPTH} superclasses inherits the field and the method declared at
     * the top of the chain.
     *
     * @param tempDir
     *            the directory to write the classfiles to
     * @throws Exception
     *             if a classfile could not be written, or a query failed
     */
    @Test
    public void deepSuperclassChain(@TempDir final File tempDir) throws Exception {
        for (var i = 0; i < DEPTH; i++) {
            final var isTop = i == DEPTH - 1;
            Files.write(new File(tempDir, "C" + i + ".class").toPath(), classfileBytes("C" + i,
                    isTop ? "java/lang/Object" : "C" + (i + 1), null, /* isInterface = */ false, isTop));
        }
        try (var scanResult = new ClassGraph().enableClassInfo().enableMethodInfo().enableFieldInfo()
                .enableClasspathEntries(tempDir).scan()) {
            final var classInfo = scanResult.getClassInfo("C0");
            assertThat(classInfo).isNotNull();
            assertMembersAreInherited(classInfo);
        }
    }

    /**
     * A class that implements the interface at the bottom of a chain of {@link #DEPTH} superinterfaces inherits the
     * field and the method declared at the top of the chain.
     *
     * @param tempDir
     *            the directory to write the classfiles to
     * @throws Exception
     *             if a classfile could not be written, or a query failed
     */
    @Test
    public void deepSuperinterfaceChain(@TempDir final File tempDir) throws Exception {
        for (var i = 0; i < DEPTH; i++) {
            final var isTop = i == DEPTH - 1;
            Files.write(new File(tempDir, "I" + i + ".class").toPath(), classfileBytes("I" + i, "java/lang/Object",
                    isTop ? null : "I" + (i + 1), /* isInterface = */ true, isTop));
        }
        Files.write(new File(tempDir, "Impl.class").toPath(),
                classfileBytes("Impl", "java/lang/Object", "I0", /* isInterface = */ false, false));
        try (var scanResult = new ClassGraph().enableClassInfo().enableMethodInfo().enableFieldInfo()
                .enableClasspathEntries(tempDir).scan()) {
            final var classInfo = scanResult.getClassInfo("Impl");
            assertThat(classInfo).isNotNull();
            assertMembersAreInherited(classInfo);
        }
    }
}
