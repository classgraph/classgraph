package io.github.classgraph.classpath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for the immutable commandline snapshot represented by {@link ModulePathInfo}. */
public class ModulePathInfoTest {
    /**
     * Command line arguments with no module switch give an instance with nothing in it, which prints as the empty
     * string rather than as a bare switch.
     */
    @Test
    public void argumentsWithNoModuleSwitchGiveAnEmptyInstance() {
        final var modulePathInfo = new ModulePathInfo(List.of("-Xmx1g", "-Dmodule-path=x"));
        assertThat(modulePathInfo.getModulePath()).isEmpty();
        assertThat(modulePathInfo.getAddModules()).isEmpty();
        assertThat(modulePathInfo.getPatchModules()).isEmpty();
        assertThat(modulePathInfo.getAddExports()).isEmpty();
        assertThat(modulePathInfo.getAddOpens()).isEmpty();
        assertThat(modulePathInfo.getAddReads()).isEmpty();
        assertThat(modulePathInfo).hasToString("");
    }

    /**
     * When {@code --module-path} is given more than once, only the last occurrence is kept, as the JVM does. Every
     * occurrence of the other switches is kept, since the JVM adds them together.
     */
    @Test
    public void onlyTheLastModulePathIsKept() {
        final var modulePathInfo = new ModulePathInfo(List.of("--module-path=a" + File.pathSeparator + "b",
                "--add-opens=java.base/java.lang=ALL-UNNAMED", "--module-path=c", "--add-modules=java.sql",
                "--add-opens=java.base/java.io=ALL-UNNAMED", "--add-modules=java.xml"));
        assertThat(modulePathInfo.getModulePath()).containsExactly("c");
        assertThat(modulePathInfo.getAddModules()).containsExactly("java.sql", "java.xml");
        assertThat(modulePathInfo.getAddOpens()).containsExactly("java.base/java.lang=ALL-UNNAMED",
                "java.base/java.io=ALL-UNNAMED");
        assertThat(modulePathInfo).hasToString("--module-path=c --add-modules=java.sql,java.xml"
                + " --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.io=ALL-UNNAMED");
    }

    /**
     * Every getter returns an unmodifiable view, so that a caller cannot change the instance that every other
     * caller shares.
     */
    @Test
    public void gettersReturnUnmodifiableSets() {
        final var modulePathInfo = new ModulePathInfo(List.of("--module-path=a", "--add-modules=b",
                "--patch-module=c=d", "--add-exports=e/f=g", "--add-opens=h/i=j", "--add-reads=k=l"));
        assertThatThrownBy(() -> modulePathInfo.getModulePath().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> modulePathInfo.getAddModules().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> modulePathInfo.getPatchModules().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> modulePathInfo.getAddExports().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> modulePathInfo.getAddOpens().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> modulePathInfo.getAddReads().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /**
     * The module switches this JVM was launched with are read from the runtime once, into one instance that every
     * caller shares, without the reflective call to {@code java.lang.management} failing on a runtime that has it.
     * Which switches are found depends on how the test JVM was launched, so the contents are not asserted on here;
     * the child JVM tests below check them.
     */
    @Test
    public void theCommandlineSwitchesAreReadFromTheRuntimeIntoOneInstance() {
        final var modulePathInfo = ModulePathInfo.current();
        assertThat(ModulePathInfo.current()).isSameAs(modulePathInfo);
        assertThat(ModulePathInfo.current().getAddOpens()).isEqualTo(modulePathInfo.getAddOpens());
    }

    // -----------------------------------------------------------------------------------------------------------

    /**
     * Run {@link ModulePathInfoPrinter} in a child JVM launched with the given JVM options, on the classpath of the
     * currently-running JVM.
     *
     * @param jvmOptions
     *            the JVM options to launch the child JVM with.
     * @return the {@code <field>TAB<value>} lines the child JVM printed.
     * @throws Exception
     *             if the child JVM could not be run.
     */
    private static List<String> runChildJvm(final List<String> jvmOptions) throws Exception {
        final List<String> command = new ArrayList<>();
        command.add(ProcessHandle.current().info().command()
                .orElseGet(() -> Path.of(System.getProperty("java.home"), "bin", "java").toString()));
        command.addAll(jvmOptions);
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(ModulePathInfoPrinter.class.getName());
        final var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        final String output;
        try (var inputStream = process.getInputStream()) {
            output = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(process.waitFor()).as("Child JVM output:%n%s", output).isZero();
        return output.lines().toList();
    }

    /**
     * The values the child JVM printed for one field.
     *
     * @param outputLines
     *            the lines the child JVM printed.
     * @param fieldName
     *            the name of the field.
     * @return the values of the field.
     */
    private static List<String> valuesOf(final List<String> outputLines, final String fieldName) {
        return outputLines.stream().filter(line -> line.startsWith(fieldName + "\t"))
                .map(line -> line.substring(fieldName.length() + 1)).toList();
    }

    /**
     * Every module switch the JVM was launched with is read from the commandline, with the switches that take a
     * delimited list split into their parts and the rest kept whole, and each is printed back in the form it was
     * given in.
     *
     * @param tempDir
     *            a temporary directory to use as the module path and the patch directory.
     * @throws Exception
     *             if the directories could not be created, or the child JVM could not be run.
     */
    @Test
    public void everyModuleSwitchOnTheCommandlineIsRead(@TempDir final Path tempDir) throws Exception {
        final var moduleDir = Files.createDirectory(tempDir.resolve("modules"));
        final var otherModuleDir = Files.createDirectory(tempDir.resolve("more-modules"));
        final var patchDir = Files.createDirectory(tempDir.resolve("patch"));
        final var otherPatchDir = Files.createDirectory(tempDir.resolve("more-patch"));
        final var patchDirs = patchDir + File.pathSeparator + otherPatchDir;
        final var outputLines = runChildJvm(List.of( //
                "--module-path=" + moduleDir + File.pathSeparator + otherModuleDir, //
                "--add-modules=java.sql,java.xml", //
                "--patch-module=java.xml=" + patchDirs, //
                "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED", //
                "--add-opens=java.base/java.lang=ALL-UNNAMED", //
                "--add-reads=java.base=ALL-UNNAMED"));

        // The module path is delimited by the path separator, and --add-modules by commas
        assertThat(valuesOf(outputLines, "modulePath")).containsExactly(moduleDir.toString(),
                otherModuleDir.toString());
        assertThat(valuesOf(outputLines, "addModules")).containsExactly("java.sql", "java.xml");
        // The rest take one value per switch, which is kept whole even when it contains a path separator
        assertThat(valuesOf(outputLines, "patchModules")).containsExactly("java.xml=" + patchDirs);
        assertThat(valuesOf(outputLines, "addExports")).containsExactly("java.base/jdk.internal.misc=ALL-UNNAMED");
        assertThat(valuesOf(outputLines, "addOpens")).containsExactly("java.base/java.lang=ALL-UNNAMED");
        assertThat(valuesOf(outputLines, "addReads")).containsExactly("java.base=ALL-UNNAMED");

        assertThat(valuesOf(outputLines, "commandline")).containsExactly("--module-path=" + moduleDir
                + File.pathSeparator + otherModuleDir + " --add-modules=java.sql,java.xml" //
                + " --patch-module=java.xml=" + patchDirs //
                + " --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED" //
                + " --add-opens=java.base/java.lang=ALL-UNNAMED" //
                + " --add-reads=java.base=ALL-UNNAMED");
    }

    /**
     * A module switch given in the short form {@code -p}, or with its value as a separate commandline argument
     * rather than after an {@code '='}, is still read: the runtime reports every form of a switch to
     * {@code getInputArguments()} in the same normalized {@code --switch=value} form.
     *
     * @param tempDir
     *            a temporary directory to use as the module path.
     * @throws Exception
     *             if the directory could not be created, or the child JVM could not be run.
     */
    @Test
    public void theShortAndSpaceSeparatedFormsOfAModuleSwitchAreRead(@TempDir final Path tempDir) throws Exception {
        final var moduleDir = Files.createDirectory(tempDir.resolve("modules"));
        final var outputLines = runChildJvm(List.of("-p", moduleDir.toString(), "--add-modules", "java.sql"));

        assertThat(valuesOf(outputLines, "modulePath")).containsExactly(moduleDir.toString());
        assertThat(valuesOf(outputLines, "addModules")).containsExactly("java.sql");
        assertThat(valuesOf(outputLines, "commandline"))
                .containsExactly("--module-path=" + moduleDir + " --add-modules=java.sql");
    }

    /**
     * When {@code --module-path} is given more than once, the JVM uses only the last occurrence, so only that one
     * is reported. The other module switches add to each other, so every occurrence of them is reported.
     *
     * @param tempDir
     *            a temporary directory to hold the two module directories.
     * @throws Exception
     *             if the directories could not be created, or the child JVM could not be run.
     */
    @Test
    public void onlyTheLastModulePathSwitchIsRead(@TempDir final Path tempDir) throws Exception {
        final var firstModuleDir = Files.createDirectory(tempDir.resolve("first-modules"));
        final var lastModuleDir = Files.createDirectory(tempDir.resolve("last-modules"));
        final var outputLines = runChildJvm(List.of("--module-path=" + firstModuleDir, "--add-modules=java.sql",
                "-p", lastModuleDir.toString(), "--add-modules=java.xml"));

        assertThat(valuesOf(outputLines, "modulePath")).containsExactly(lastModuleDir.toString());
        assertThat(valuesOf(outputLines, "addModules")).containsExactly("java.sql", "java.xml");
        assertThat(valuesOf(outputLines, "commandline"))
                .containsExactly("--module-path=" + lastModuleDir + " --add-modules=java.sql,java.xml");
    }

    /**
     * On a runtime without the {@code java.management} module, the commandline switches cannot be read, so every
     * set is empty, rather than reading them throwing an error.
     *
     * @throws Exception
     *             if the child JVM could not be run.
     */
    @Test
    public void nothingIsReadWithoutTheManagementModule() throws Exception {
        final var outputLines = runChildJvm(
                List.of("--limit-modules", "java.base", "--add-opens", "java.base/java.lang=ALL-UNNAMED"));

        // The --add-opens switch would be read if java.management were present
        assertThat(valuesOf(outputLines, "addOpens")).isEmpty();
        assertThat(valuesOf(outputLines, "commandline")).containsExactly("");
    }
}
