package io.github.classgraph.vfs;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;

/** Directory depth does not consume the traversal thread's Java call stack. */
public class VfsDeepDirectoryTest {
    /** Walking and cached enumeration share a stack-safe traversal, independent of host path-length limits. */
    @Test
    public void deepDirectoriesCanBeWalkedWithASmallStack() throws Exception {
        try (var fileSystem = Jimfs.newFileSystem(Configuration.unix()); var vfs = new Vfs()) {
            final var dir = Files.createDirectory(fileSystem.getPath("/root"));
            final var depth = 1000;
            var deepest = dir;
            for (var i = 0; i < depth; i++) {
                deepest = Files.createDirectory(deepest.resolve("x"));
            }
            Files.writeString(deepest.resolve("f"), "content");
            final var expected = "x/".repeat(depth) + "f";
            final var root = vfs.open(dir);
            final var task = new FutureTask<>(() -> {
                final List<String> visited = new ArrayList<>();
                root.walk(new VfsVisitor() {
                    @Override
                    public boolean enterDirectory(final String name) {
                        if (!name.equals("/")) {
                            assertThat(expected).startsWith(name);
                        }
                        return true;
                    }

                    @Override
                    public boolean visitEntry(final VfsEntry entry) {
                        visited.add(entry.getPathFromRoot());
                        return true;
                    }
                });
                assertThat(visited).containsExactly(expected);
                return root.getEntries().stream().map(VfsEntry::getPathFromRoot).toList();
            });
            final var thread = new Thread(null, task, "directory-small-stack", 256 * 1024);
            thread.setDaemon(true);
            thread.start();
            try {
                assertThat(task.get(60, TimeUnit.SECONDS)).containsExactly(expected);
            } finally {
                thread.interrupt();
                thread.join(5000);
            }
        }
    }
}
