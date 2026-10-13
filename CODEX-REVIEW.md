# Whole-codebase review of main

Reviewed revision: `6b474939f02ac4b332e82e5fa57eb0289723b26d` (`6.0.0-SNAPSHOT`).
Review date: 2026-10-12. Runtime used for verification: Oracle JDK 26.0.2, Linux.

This report preserves the original findings against the reviewed revision. The remediation log at the end records subsequent fixes, tests, compatibility decisions, and the additional recursive-VFS verification; descriptions in the original findings are not claims that the same defects remain in later commits.

This is a review of the current Java-25-baseline code, not a restatement of the earlier v5 review. Production code was not changed. Findings distinguish reproduced behavior, conclusions from source inspection, and optional architectural improvements. P2 means an actionable correctness/performance/API issue; P3 means a smaller defect or a design/hardening opportunity. No P0/P1 issue was established by this review.

## Executive summary

The module split, explicit configuration, immutable info lists, bounded parser inputs, and explicit memory-mapping ownership are substantial strengths. The main remaining weaknesses are mismatched definitions of inheritance, an overaggressive module-package lookup optimization, and the gap between a file-entry-oriented VFS and the contracts expected of a general NIO filesystem.

The existing test suite passes, but independent probes expose failures in these areas. The highest-value fixes are R01–R05 and R07: effective-member queries and reverse indexes should agree, unscanned-module lookup must consider all relevant layers, filesystem closure must close its own handles, manifest traversal should be iterative, and `Path.toUri()` must identify its provider.

| ID | Priority | Area | Finding |
| --- | --- | --- | --- |
| R01 | P2 | Queries | Reverse member-annotation queries include overridden/hidden members |
| R02 | P2 | Queries | Static interface methods appear inherited by implementing classes |
| R03 | P2 | Modules | Unscanned-module lookup loses classes in packages shared across layers |
| R04 | P2 | NIO lifecycle | Closing a filesystem view leaves its channels and streams readable |
| R05 | P2 | Scanner | Manifest dependency traversal still overflows the call stack |
| R06 | P2 | Lists | An identity filter removes duplicate declared exception entries |
| R07 | P2 | NIO paths | `toUri()` returns a scheme different from the provider's scheme |
| R08 | P2 | NIO identity | `isSameFile()` substitutes lexical normalization for file identity |
| R09 | P2 | NIO paths | Cross-filesystem relativization produces a misleading relative path |
| R10 | P2 | NIO iteration | Directory streams violate filtering and read-ahead contracts |
| R11 | P2 | NIO namespace | Empty directories disappear; fresh views retain stale directory listings |
| R12 | P2 | Memory | Closed channels retain their inflated content buffers |
| R13 | P2 | Performance/docs | `transferTo()` is not bounded-memory for compressed/module entries |
| R14 | P2 | Performance | A single NIO file lookup indexes the entire root |
| R15 | P2 | Performance | Method override ordering repeatedly expands transitive interface graphs |
| R16 | P2 | Reflection | Optional-driver initialization swallows fatal VM errors |
| R17 | P3 | NIO channels | A zero-length scattering read succeeds on a closed channel |
| R18 | P2 | Documentation | The module-source overview contradicts the explicit configuration checks |
| R19 | P3 | Modernity/comments | GC recovery comments still assume cleaner-managed mappings |
| R20 | P3 | Performance | Module-resource size metadata materializes and repeatedly reads the content |
| R21 | P3 | Architecture | Class identity is global by name, despite multiple loader/layer sources |
| R22 | P3 | Build hardening | CI actions are referenced by mutable version tags |
| R23 | P3 | Structure/testing | Inheritance policies are duplicated; contract/differential tests are missing |
| R24 | P3 | Benchmark | The documented `none` eviction argument is treated as an executable |

## Verification and coverage

Ran the full reactor without source rewriting:

```sh
./mvnw -Dformatter.skip=true test
```

| Module | Tests reported | Skipped | Failures/errors |
| --- | ---: | ---: | ---: |
| classgraph-base | 243 | 0 | 0 |
| classgraph-vfs | 662 | 7 | 0 |
| classgraph-classpath | 390 | 2 | 0 |
| classgraph | 1,265 | 3 | 0 |
| classgraph-viz | 62 | 0 | 0 |
| Total | 2,622 | 12 | 0 |

Inspected production architecture and targeted implementations across all five modules: concurrency/progress/error propagation, reflection drivers, path handling, filesystem ownership, directory walking, ZIP and nested-JAR parsing, inflation limits, sequential/random readers, NIO adaptation, classloader/module discovery, classpath expansion, scan ordering, classfile/signature/annotation parsing, metadata/list APIs, graph queries, and GraphViz generation. Also inspected Maven configuration, repository documentation, CI, and benchmark drivers. This is risk-directed review, not a claim that every line was formally verified.

Independent temporary Java probes were compiled against the reactor's `target/classes`, outside the repository. They tested inheritance queries, exception-list filtering, NIO contracts, compressed-channel buffering, real independent module layers with a shared package, a 1,200-JAR manifest chain, generated interface chains, and synthetic fatal optional-driver initialization. They did not require real memory exhaustion. Selected outputs appear below.

Limitations: no Windows/macOS execution, no separate JDK 25 run, no exhaustive fuzz campaign, no production-scale heap profile, no new CodeQL run, and no verification of every supported third-party container's current classloader implementation. Remote wiki content is not part of this report's verified scope; repository README/Javadoc were reviewed. Findings refer to source line numbers at the reviewed revision.

## Correctness and behavioral findings

### R01 — Reverse member-annotation queries do not account for overriding or hiding [P2]

Evidence: `classgraph/src/main/java/io/github/classgraph/ClassInfo.java:2776`, particularly the unconditional descendant expansion; inheritance eligibility at `ClassInfo.java:856`.

After finding a class with a non-private annotated member, the reverse query adds all subclasses and implementing classes. It does not check whether the particular member is overridden, hidden, or unavailable across a package boundary. The graph records the declaring class but not enough member identity to make that decision. Method-parameter queries use the same expansion.

Reproduced with:

```java
class Base { @Mark public void ping() {} @Mark public int value; }
class Child extends Base { @Override public void ping() {} public int value; }
```

Both `getClassesWithMethodAnnotation(Mark.class)` and `getClassesWithFieldAnnotation(Mark.class)` include `Child`, while filtering `Child.getMethodInfo()` and `Child.getFieldInfo()` for `Mark` yields zero members. The documentation says the extra classes inherit the member, which is not true for these effective-member views.

Impact: framework discovery can select classes that have no matching effective annotated member. Package-private declarations across packages are another source-level concern when method/field visibility is ignored; the current non-private predicate does not inspect the receiving class's package.

Recommendation: index annotated member identities, or use the declaring-class index as a candidate set and validate candidates against one shared effective-member resolver. Test method overrides, field hiding, parameter annotations, package boundaries, default methods, and external/rejected declaring classes. If a deliberately broader “descendants of declaring classes” query is desired, give it a separate name and contract.

### R02 — Forward method lookup inherits static interface methods [P2]

Evidence: `ClassInfo.java:3025` and `ClassInfo.java:3074`; compare with the existing interface-static exclusion in `ClassInfo.java:856`.

`mergeDeclaredMethodsInOverrideOrder()` merges declarations from every class/interface in the order without checking whether each method can actually be inherited. A public static interface method therefore appears on its implementing class.

```java
interface I { static void helper() {} }
class Impl implements I {}
```

Reproduced: `Impl`'s `ClassInfo.getMethodInfo("helper")` is nonempty. Java does not inherit static interface methods into implementing classes. The reverse annotation index already knows this rule, making the forward and reverse APIs inconsistent. See [JLS method inheritance](https://docs.oracle.com/javase/specs/jls/se25/html/jls-8.html#jls-8.4.8).

Recommendation: retain all applicable declarations for the class being queried, but filter ancestor declarations using receiving-class-aware inheritance rules. Exercise both the named and unnamed method queries. Also explicitly define the treatment of private ancestor methods when `ignoreMethodVisibility()` is enabled; scanning a declaration is distinct from inheriting it.

### R03 — Package-to-module lookup incorrectly assumes uniqueness across layers [P2]

Evidence: `classgraph/src/main/java/io/github/classgraph/UnscannedModules.java:119` and `:160`; layer aggregation in `classgraph-classpath/src/main/java/io/github/classgraph/classpath/internal/ModuleFinder.java:165`.

The lookup map stores only the first `ModuleReference` for each package. If the requested class is not in that module, lookup stops. The comment invokes package uniqueness within a layer, but the input spans multiple layers. Independent layers can legitimately have modules containing the same package with different classes. First-wins masking of a *class name* does not justify masking every other class in its package.

Reproduced with two independent layers: module `review.foo` exports `p` and contains only `p.Other`; module `review.bar` exports `p` and contains `p.Base`, with a public method `inherited()`. A scanned classpath class `q.Accepted` extends `p.Base`.

With those layers enabled but their modules narrowed out by `acceptModules("no.such.module")`, the supposedly lookup-only external `p.Base` has zero declared `inherited` methods. With the modules accepted, it has one. These modules are not rejected, so the documented upward graph completion should still work.

Recommendation: map each package to an ordered list of candidate modules and look up the requested class until found, retaining first-wins semantics for actual duplicate class names. Add a multi-layer shared-package regression test. Correct the uniqueness comment and the claim that package-level first-wins behavior matches a full module scan.

### R04 — Filesystem-view closure does not close that view's open handles [P2]

Evidence: `classgraph-vfs/src/main/java/io/github/classgraph/vfs/VfsFileSystem.java:351`, provider open methods at `VfsFileSystemProvider.java:811`, and channel registration at `VfsFileChannel.java:98`.

A borrowed filesystem view clears its index and marks itself closed, but its channels/streams are registered only with the backing root. When the root remains open, these handles remain usable after the view is closed.

Reproduced using `vfs.open(directory).asFileSystem()`:

```text
channel open after filesystem close: true
channel read after filesystem close: 1
stream read after filesystem close: 97
```

This violates the [FileSystem closure contract](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/file/FileSystem.html#close()), which requires associated closeable objects to be closed. Independent root/view ownership is reasonable, but does not exempt view-created handles from this contract. Directory streams likewise have no view-lifecycle registration.

Recommendation: track handles per filesystem view as well as per root, with atomic registration/close handoff. Closing a view must release its handles while leaving the root and handles belonging to other views untouched. Test concurrent close/open, ordinary borrowed views, provider-owned filesystems, streams, channels, and directory streams.

### R05 — Scanner dependency ordering is still recursively traversed [P2]

Evidence: `classgraph/src/main/java/io/github/classgraph/Scanner.java:300`; contrast with the explicit-stack traversal in `classgraph-classpath/src/main/java/io/github/classgraph/classpath/TransitiveClasspath.java:122`.

The scanner recursively follows manifest `Class-Path` dependencies when producing the final order. Its visited set breaks cycles but does not bound the stack depth of an acyclic chain. The classpath-finder module has already solved this exact problem iteratively, so the two public entry points differ.

Reproduced with 1,200 small JARs, each manifest naming the next JAR, using a supported small Java stack (`-Xss256k`) and `scan(1)`:

```text
classpath finder completed
scanner StackOverflowError
```

The precise failing depth varies with JVM stack size; the underlying unbounded recursion is not specific to 1,200 entries.

Recommendation: preserve the package-root/preorder rules using explicit traversal frames, and share ordering semantics with `TransitiveClasspath` where possible. Audit the remaining input-depth-dependent recursion in `ModuleFinder.findLayerOrder`, `DirRoot.walkRecursively`, and `ClasspathEntryFlattener` too; those additional sites were identified by inspection, not reproduced here.

### R06 — `ClassInfoList.filter()` silently deduplicates declaration-order exception lists [P2]

Evidence: `classgraph/src/main/java/io/github/classgraph/ClassInfoList.java:59` and `:316`.

Exception lists deliberately preserve repeated entries, but `filter()` collects into a `LinkedHashSet`. An identity filter is therefore not an identity operation.

```java
public <T extends Exception, U extends Exception> void twice() throws T, U {}
```

Reproduced: `getThrownExceptions().size()` is 2, but `.filter(ci -> true).size()` is 1. `getStandardClasses()` and other helpers implemented through `filter()` inherit this behavior. Explicit set operations such as `union()` are a different matter and can reasonably deduplicate.

Recommendation: preserve occurrence order and multiplicity in filtering, while separately preserving direct-relatedness metadata. Keep construction immutable; this finding does not call for restoring list mutators. Add identity-filter tests for declaration-order lists with duplicate erased exception types.

### R07 — `VfsPath.toUri()` violates the provider-scheme requirement [P2]

Evidence: `classgraph-vfs/src/main/java/io/github/classgraph/vfs/VfsPath.java:339` and `:350`.

`toUri()` deliberately returns the underlying storage URI (`file:`, `jar:`, or `jrt:`), while this path belongs to the `cgvfs` provider. The [Path API](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/file/Path.html#toUri()) requires the URI scheme to identify the path's provider. This requirement is distinct from the default-provider-only round-trip equality guarantee.

Reproduced for a directory-backed path:

```text
path URI scheme/provider scheme: file/cgvfs
path URI roundtrip filesystem unchanged: false
```

The storage URI is useful, but generic code converting `Path` to `URI` and back now changes filesystem semantics. A directory path can become a writable default-filesystem path rather than the read-only virtual path. Nested or module paths can similarly select an unrelated provider or fail to resolve.

Recommendation: make `toUri()` provider-native and expose the storage URI through an explicitly named API. Preserve the documented registration limitations of borrowed/path-created views; provider-native URIs do not require promising universal round-trip equality. Review `toCgvfsUri()` and documentation together so callers do not need a subtype cast merely to obtain the standard provider-identifying URI.

### R08 — `isSameFile()` conflates normalized path spelling with actual identity [P2]

Evidence: `classgraph-vfs/src/main/java/io/github/classgraph/vfs/VfsFileSystemProvider.java:966`.

The implementation compares normalized absolute paths, without consulting either entry. Reproduced:

```text
missing aliases same file: true
symlink aliases same file: false
```

The first pair is `/missing` and `/./missing`, neither of which exists. They are not equal `Path` objects, so the documented equal-path existence shortcut does not explain the result. The second pair is an existing directory-backed file and a symbolic link to it, both readable through the VFS. Separate filesystem views of the same underlying file also fail lexical equality because equality includes view identity.

Impact: identity/deduplication utilities receive false positives or false negatives. This implementation also never validates the second view's lifecycle. See [Files.isSameFile](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/file/Files.html#isSameFile(java.nio.file.Path,java.nio.file.Path)).

Recommendation: keep the exact-equality shortcut, then resolve/check the entries and compare an appropriate underlying identity. Delegate directory-backed comparisons to their native provider. Define archive/module identity deliberately rather than making normalization stand in for file existence and identity.

### R09 — `relativize()` ignores filesystem identity [P2]

Evidence: `classgraph-vfs/src/main/java/io/github/classgraph/vfs/VfsPath.java:125` and `:308`.

`check()` checks only the path implementation class. `/a` in one root can be relativized against `/b` in a different root, yielding `../b` in the first filesystem. Resolving that result cannot navigate to the second root: it addresses the first root's `/b` instead. `startsWith`, `endsWith`, and equality, in contrast, distinguish filesystem identities.

Reproduced: `fs1.getPath("/a").relativize(fs2.getPath("/b"))` returns `../b`. The JDK ZIP provider rejects analogous nonempty cross-filesystem relativization.

Recommendation: reject cross-view/root relativization with `IllegalArgumentException`, unless an explicitly documented shared-identity model is introduced. Test different roots, different views of one root, and empty paths. `resolve(Path)` also accepts foreign relative VFS paths, but resolution is provider-dependent and JDK ZIP paths do this too; it is not separately claimed here as a mandatory contract violation.

### R10 — Directory-stream filtering and read-ahead semantics are wrong [P2]

Evidence: `VfsFileSystemProvider.java:850`, `:912`, and `:919`.

Two independently reproduced problems:

1. Filters run eagerly inside `newDirectoryStream()`. A filter's `IOException` escapes from stream creation as `IOException`, rather than from iterator `hasNext()`/`next()` as `DirectoryIteratorException`. Runtime exceptions/errors likewise occur at the wrong boundary. [Files specifies this behavior explicitly](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/file/Files.html#newDirectoryStream(java.nio.file.Path,java.nio.file.DirectoryStream.Filter)).
2. `hasNext()` returns true, but closing the stream immediately afterward causes `next()` to throw `NoSuchElementException`. [DirectoryStream guarantees read-ahead](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/file/DirectoryStream.html): closing must not invalidate the element already promised by `hasNext()`.

The comments say closed iterators report end-of-directory, but omit the important read-ahead exception to that rule. Eager filtering also evaluates every child even when the caller only needs the first match.

Recommendation: implement a thread-safe iterator with a buffered accepted element, lazy filtering, and correct exception translation. Closing stops future reads but preserves an already-buffered element. Add tests for checked/runtime filter failures, early termination, and `hasNext()` → close → `next()`.

### R11 — Directory-backed NIO views omit empty directories and retain stale names [P2, semantic gap]

Evidence: `VfsFileSystem.java:101` and `:173`; `classgraph-vfs/src/main/java/io/github/classgraph/vfs/DirRoot.java:143` and `:286`.

The adapter synthesizes directories only from file names. A real empty directory under a `DirRoot` therefore does not exist in the filesystem view. This limitation follows from the documented file-only VFS listing, but is surprising when presented through `Files.exists`, `Files.isDirectory`, or `Files.walk`.

There is also no coherent live/snapshot boundary: `DirRoot.getEntry()` directly sees newly created files, while its cached `getEntries()` list does not. Even closing the view and obtaining a fresh view reuses that cached namespace.

Reproduced:

```text
empty directory exists: false
root direct lookup sees later: true
fresh filesystem sees later: false
```

Recommendation: preserve explicit directory nodes for directory-backed views and define freshness. Prefer native directory lookups/listings where possible. If the view is intentionally a file-derived namespace snapshot, describe that limitation prominently, including that a fresh *view* is not a fresh *root* snapshot. Avoid describing it as a transparent directory filesystem.

### R12 — Closing a buffered channel does not release its content buffer [P2]

Evidence: `VfsFileChannel.java:57` and `:309`; `VfsEntry.java:501`; `reader/RandomAccessOrSequentialReader.java:932`.

The channel holds `RandomAccessContent` in a final field. Closing invokes `reader.close()`, which closes/nulls the input stream but does not clear `arr`. A caller retaining a closed channel consequently retains all previously inflated bytes. The content/close-action documentation describes these buffers as released on close, but this buffer is not released while the closed channel remains reachable.

Reproduced with a 16 MiB deflated entry transferred to a discard sink. Inspecting the channel's retained reader showed:

```text
retained buffer before close=16777216
retained buffer after close=16777216
```

Recommendation: let the channel detach its reader/close action after closure, or give the owned reader a buffer-release operation. Do not blindly change standalone reader semantics: it currently permits access to already-buffered bytes after close. Coordinate any release with in-progress reads. Test heap-retention behavior, not just whether the input stream was closed.

### R13 — Compressed `transferTo()` still retains the entire transferred prefix [P2, performance/documentation]

Evidence: `VfsFileChannel.java:240`, especially its bounded-buffer comment; `VfsEntry.java:501`; `RandomAccessOrSequentialReader.java:481`.

The 16 KiB transfer scratch buffer does not bound the total memory cost. Compressed entries and module resources are read through a reader that caches everything up to the greatest requested offset. A complete sequential transfer therefore retains O(entry size) heap, and cannot stream beyond the reader's approximately 2 GiB array limit. Stored/directory entries use different readers and do not have this problem.

The 16 MiB probe transferred 16 MiB but also retained a 16 MiB inflation cache. The comment that transferring a large entry does not require an entry-sized buffer is false for this backing-reader path. The general reader's buffering behavior is documented; the misleading transfer comment and the surprising transfer cost remain.

Recommendation: at minimum document total memory/size behavior on the channel/provider API and direct streaming users to `newInputStream()`/`VfsEntry.open()`. Consider a streaming transfer path or bounded/spilled random-access cache, with explicit tradeoffs for repeated positional reads. Do not claim zero-copy or constant-memory behavior from the scratch-buffer size alone.

### R14 — Every first NIO lookup performs whole-root enumeration [P2, performance]

Evidence: `VfsFileSystem.java:173` and the provider's `entryOf`/attribute paths; compare `DirRoot.java:286`.

Looking up one known file through the NIO adapter builds an index of every file and inferred directory in the root. For directory roots that means recursive enumeration and metadata work across unrelated subtrees, plus retained maps/sets/lists. Native `VfsRoot.getEntry()` can perform a direct lookup without that traversal. The cost is acknowledged in `VfsRoot.asFileSystem()` documentation, so this is an architectural performance limitation rather than an undocumented algorithmic surprise.

Impact: a header read or `Files.exists()` against one file in a large exploded tree pays O(total files), and listing one directory does not prune unrelated directories. The adapter discards much of the advantage of the visitor API's pruning/early-stop design. Archive central-directory enumeration is already necessary, but rebuilding multiple indexes still has memory cost.

Recommendation: separate entry lookup from directory-tree materialization. Use direct root lookup for files and incremental/native directory listing for directory roots; reserve full indexing for roots that require it. Benchmark first-file lookup and partial directory traversal, not only full scanner throughput.

### R15 — Method override ordering has avoidable superlinear work [P2, performance]

Evidence: `ClassInfo.java:2252`, `:2287`, and `:2500`.

For every interface visited, the algorithm reconstructs its complete transitive superinterface collection, probes existing order positions with linear `indexOf()`, and pushes the transitive collection again. The visited set prevents duplicate output but does not avoid transitive expansion and duplicate stack work. Unlike the concrete-class branch, the interface branch is not restricted to direct edges.

Generated method-free interface chains gave these first-query times, excluding the scan:

| Interface depth | `getMethodInfo()` time |
| ---: | ---: |
| 1,000 | 231 ms |
| 2,000 | 492 ms |
| 4,000 | 1,540 ms |

These are single-process cold-query observations, not controlled benchmark results or proof of a specific empirical exponent. Source inspection nevertheless establishes repeated transitive expansion and linear position searches; some graph/order combinations can add substantially more work than a direct-edge traversal. Caching the final order helps repeated queries on the same class, not the first query or queries on many related classes.

Recommendation: traverse direct edges and compute specificity through an explicit stable topological ordering, preserving superclass-before-interface precedence. Benchmark chains, diamonds, wide interface graphs, and querying all classes. Keep ordering-correctness tests alongside the performance tests.

### R16 — Optional reflection-driver initialization swallows `VirtualMachineError` [P2]

Evidence: `classgraph-base/src/main/java/io/github/classgraph/base/internal/reflection/ReflectionUtils.java:71` and `:78`; compare its correctly guarded `fail()` at `:143`.

Ordinary reflective calls correctly rethrow fatal VM errors, including wrapped errors. The initial Narcissus selection path does not: it catches any `Throwable`, prints a warning, and selects standard reflection even for `OutOfMemoryError` or `StackOverflowError`.

Reproduced in a separate JVM with a synthetic `io.github.toolfactory.narcissus.Narcissus` class whose static initializer throws `OutOfMemoryError`:

```text
ClassGraph could not use the Narcissus reflection driver: java.lang.OutOfMemoryError: synthetic optional driver initialization
continued after fatal initialization
```

Recommendation: run the existing fatal-error rethrow helper before logging/falling back, while retaining fallback for ordinary optional-library/native-link failures. Test this in an isolated JVM because driver selection is static and happens once. Do not broadly report every `catch(Throwable)` as defective: the invocation path was explicitly tested and handles this correctly.

### R17 — Closed zero-length scattering reads return success [P3]

Evidence: `VfsFileChannel.java:130`.

The scattering overload validates the range and calls the scalar read inside a loop. With `length == 0`, the loop never reaches `checkOpen()`, so a closed channel returns 0. Reproduced with `ch.close(); ch.read(new ByteBuffer[0], 0, 0)`.

Recommendation: check channel state in the overload itself, including the zero-length case. Add a small contract matrix covering closed channels, empty buffers, EOF, and invalid ranges.

## Documentation, modernity, and architectural findings

### R18 — The source-selection overview gives invalid module configuration advice [P2]

Evidence: `README.md:83` and `:88`; `ClassGraph.java:72`; `classgraph-classpath/.../ClasspathFinder.java:54`; actual checks at `classpath/internal/ScanSourceSpec.java:313`.

The overview treats module enabling as the same no-arguments/varargs pair as classpath enabling, claiming the varargs method alone scans named layers and calling both adds the environment. In reality, `enableModuleLayers(layer)` only chooses layers; a module-kind method such as `enableNonSystemModules()` is also required. Adding that method does *not* add detected layers. `enableDetectedModuleLayers()` is the separate option that does that.

An attempted scan with only named module layers throws:

```text
ClassGraph#enableModuleLayers() has no effect unless ClassGraph#enableSystemModules() or ClassGraph#enableNonSystemModules() is also called
```

The method-level Javadoc is correct, but the README and class-level summaries contradict it.

Recommendation: describe classpath selection and module kind/layer selection separately. Show `enableModuleLayers(layer).enableNonSystemModules()` for named non-system modules, and add `enableDetectedModuleLayers()` only when the environment's layers are also desired. Compile/run documentation examples that involve configuration interactions.

### R19 — Mapping recovery comments conflate shared arenas and cleaner-managed buffers [P3]

Evidence: `classgraph-vfs/.../internal/slice/FileMapping.java:102` and `:116`; `internal/slice/PathSlice.java:637`.

The retry rationale says unreachable mapped buffers are unmapped by their cleaner, and the broader fallback commentary includes an arena that failed to close among things GC may recover. ClassGraph's current mappings use `Arena.ofShared()`, whose lifetime is explicitly closed, not automatically cleaned when its Java references disappear. Those comments fit legacy/third-party cleaner-managed mappings, not these shared-arena mappings. See [Arena lifetime rules](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/foreign/Arena.html).

Recommendation: distinguish GC-managed mappings elsewhere in the process from mappings owned by ClassGraph. GC can still help free unrelated memory/mappings, so this is not a blanket recommendation to remove all recovery. Correct the rationale, do not suggest GC fixes an unclosed shared arena, and measure whether forcing a global GC plus a reference-processing wait is useful on the supported JDKs before retaining it on every mapping failure.

### R20 — Reading module-resource size allocates/reads substantially more than metadata [P3, performance opportunity]

Evidence: `VfsFileSystemProvider.java:1182` and `:1196`.

For an entry with unknown length, every `BasicFileAttributes.size()` invocation calls `entry.read()` and obtains its entire byte buffer. The implementation explicitly documents the repeated read, so this is not a hidden correctness bug. Still, a size query can materialize a large module resource, and querying the same returned attributes object twice performs the operation twice. String attribute selection avoids this if `size` is not requested, which is good.

Recommendation: count bytes through a bounded scratch buffer rather than requiring a whole-content buffer where the module reader lacks cheap size information. Memoize a successfully computed size on the attribute instance if snapshot semantics are intended. Test large custom module resources and distinguish checked I/O failures from the unavoidable unchecked boundary of `BasicFileAttributes.size()`.

### R21 — The class graph represents one global first-wins namespace, not JVM class identity [P3, architectural limitation]

Evidence: `Scanner.java:983` and `:1069`; name-based linking in `ClassInfo.java:464`; source APIs accepting multiple classloaders and layers.

Classfiles are masked by path and linked into a map keyed only by binary class name. Two independent loaders/layers can legitimately define distinct classes with the same name. The graph keeps one definition and can consequently link a class from a later source to a same-named ancestor from an unrelated earlier source.

First-wins classpath behavior is intentional and useful. The limitation is extending a single ordered namespace model to multiple independent classloader/layer namespaces, especially while advertising a model of what the JVM can see. Fixing this generally would be a major API/model change, not a small bug patch.

Recommendation: document the limitation explicitly and recommend separate scans for independent plugin namespaces. Preserve resource-level duplicate discovery. If loader-aware graphs are a future goal, introduce definition identity/origin separately from the name-based compatibility lookup; do not replace the existing API with an undocumented behavioral change. This review established the name-only model by inspection, not a new end-to-end mixed-loader reproduction.

### R22 — CI executes actions through mutable tags [P3, supply-chain hardening]

Evidence: `.github/workflows/ci.yml` and `.github/workflows/codeql.yml`, including `actions/checkout@v7`, `actions/setup-java@v5`, and `github/codeql-action/*@v4`.

The workflows do not pin action code to commit SHAs. A tag can move independently of this repository's reviewed changes. This matters particularly for the CodeQL job's `security-events: write` permission. No compromised action or exploit was identified; this is preventive hardening.

Recommendation: pin actions to reviewed full commit SHAs, retaining human-readable version comments, and use automated update PRs. Specify minimal workflow/job permissions explicitly. Keep the existing CodeQL permission scoping. Do not claim this alone makes builds reproducible: dependency/plugin resolution is a separate concern.

### R23 — Duplicated semantic policy and missing contract/oracle tests make regressions too easy [P3, structure/futureproofing]

Evidence: `ClassInfo.java:856`, `:2014`, `:2252`, `:2776`, and `:3025`; NIO adapter tests and the separate JDK classfile-generation fixtures.

`ClassInfo` combines mutable graph construction, relationship traversal, effective-member ordering, annotation reverse indexing, and lazy type metadata. Inheritance policy is implemented separately in forward merges and reverse relations; R01/R02 are concrete consequences. The NIO adapter likewise has bespoke implementations for lifecycle, identity, iteration, and URI semantics without a reusable contract suite that would catch R04/R07–R10/R17.

Recommendation: extract receiving-class-aware member resolution as a small internal service used by both directions. Keep immutable public lists and explicit builders; avoid splitting classes solely to reduce line count. Add a filesystem contract harness covering paths, lifetime, exception boundaries, identity, and directory iteration against the JDK's reference providers where semantics are comparable.

With a Java 25 baseline, the stable JDK classfile API is available as a differential test oracle. The repository already uses it in a small number of fixtures; expand that into generated valid classfiles and comparisons against reflection/JDK parsing for descriptors, annotations, records, and inheritance. Add bounded malformed-input mutation tests, explicitly excluding ClassGraph's intended leniency from equality assertions. This is not a recommendation to replace the optimized parser in production without measuring behavior and cost.

### R24 — Benchmark `none` eviction sentinel is not implemented [P3]

Evidence: `benchmark/Bench.java:20`, `:37`, `:54`, and `:101`.

The usage and argument documentation allow an eviction command or `none`. In cold mode the remaining arguments are always passed to `ProcessBuilder`, so `none` is treated as an executable name. In warm mode it is ignored only because no eviction command is consulted. Zero/negative run counts also reach empty-result reporting without useful validation.

Recommendation: validate argument count, mode, positive run count, and eviction-command presence up front. Either implement the sentinel with clearly labeled “no eviction” semantics or explicitly reject it in cold mode; silently calling a cache “cold” without eviction would invalidate the measurements. Keep these manually run drivers covered by small behavior tests as well as the existing compilation gate.

## Suggested remediation order

1. Establish one effective-member policy and fix R01/R02; add cross-direction consistency tests.
2. Fix module candidate lookup and iterative manifest ordering (R03/R05).
3. Treat the NIO adapter as a real provider: lifetime, URI, identity, relativization, and iterator contracts (R04/R07–R10/R17).
4. Decide the directory view's namespace/freshness semantics and avoid full indexing for simple reads (R11/R14).
5. Fix list multiplicity and channel-retained buffers (R06/R12); make transfer memory costs explicit (R13).
6. Fix fatal-error fallback and misleading source-selection/mapping documentation (R16/R18/R19).
7. Measure and simplify override ordering, improve module metadata reads, and add differential/contract coverage (R15/R20/R23).
8. Harden workflow inputs and document the remaining namespace limitation (R21/R22/R24).

## Useful verification artifacts

The full reactor log is `/tmp/classgraph-codex-review-tests.log`. Temporary probe sources are `/tmp/ClassGraphReviewProbe.java`, `/tmp/ModuleReviewProbe.java`, `/tmp/BufferReviewProbe.java`, `/tmp/DepthReviewProbe.java`, `/tmp/InterfaceReviewProbe.java`, and `/tmp/FatalDriverReviewProbe.java`; their fixtures/compiled classes are in task-specific `/tmp/classgraph-review-*20261012*` directories. These are local ephemeral artifacts, not repository dependencies. The report's examples and observations above remain meaningful if `/tmp` is cleaned.

The ordinary invocation fatal-error probe correctly propagated `OutOfMemoryError`; only optional-driver *initialization* swallowed it. No finding was raised merely because a broad catch exists. Likewise, deliberate read-only write rejection, parser nesting/length limits, immutable-list construction, and platform-thread usage were not treated as defects without evidence. Existing comments explain the virtual-thread/structured-concurrency tradeoffs; adopting preview APIs is not necessary for modernity.

## Additional requested verification

R05 follow-up: classpath-container expansion now uses explicit iterator frames and identity-based ancestor guards. `ClasspathEntryFlattenerTest` covers 12,000 alternating array/collection levels on a 256 KiB-stack thread, cyclic arrays and collections, repeated aliases, self-yielding resources, atomic paths/primitive arrays/nulls, and action failures. Main commit `93a3cae68`; v5 backport `5102f7ed0`. `ModuleFinderLayerOrderTest` adds a 1,200-layer small-stack regression, preserving the existing parent/child ordering tests. `VfsDeepDirectoryTest` exercises both visitor walking and cached enumeration of a 1,000-level tree without host path-length restrictions. A pre-fix v5 probe reproduced `StackOverflowError`; the full main reactor passed after all three traversal fixes. A real 1,000-level native directory tree also passed targeted verification; the portable regression uses Jimfs to keep CI fast.

- [x] Exercise recursive `classgraph-vfs` wrapping: `VfsRecursiveWrappingTest` covers three layers over native directories, native archives, archive package roots, and RAM-backed archives; eight layers over a module with counted metadata lookups, including alternating isolated-provider classloaders; repeated self-wrapping in one `Vfs`; provider-owned views; independently closing source views; evicted-wrapper ownership; and separate zip mounts with identical URIs. Reading, listing, metadata, lookup scaling, cache reuse, and borrowed ownership are checked.

### R25 — Recursive wrappers and independent filesystem mounts alias in the root cache

The additional requested verification found that URI-only cache keys cannot identify paths across filesystem instances. Repeatedly wrapping a view in the same `Vfs` returned its preceding root instead of wrapping the requested view. Independent views with identical storage URIs also shared a cached root and therefore the wrong source lifetime. The initial regression run failed both cases.

The fix keys non-default filesystem paths by filesystem identity and provider URI, rather than storage URI alone. Repeated opens within one filesystem still reuse a root; separate views and separate zip mounts remain independent. Storage URIs continue to be used for scheme policy and reporting, not filesystem identity. Public `Vfs.open(Path)` documentation now explicitly describes recursive wrapping and the borrowed source lifetime. This additional issue applies to v6/v5, not the legacy v4 API.

## Remediation log

### R27 — Recursive filesystem views multiply metadata lookups

The deeper module-backed wrapping test found that `DirRoot.pathForNio()` rechecked existence and real-path case at every layer, even when its backing path was already a `VfsPath` in ClassGraph's case-sensitive namespace. Each redundant check traversed the inner views again, producing exponential lookup work. Reading one resource through eight view layers required **9,841** module-resource URI lookups in the pre-fix regression.

The follow-up fix delegates exact-name validation to the source view for `VfsPath` backing paths, while retaining the existing checks for native and foreign-provider paths. `deepModuleWrappersDoNotMultiplyResourceLookups` verifies the content and bounds those lookups by `4 * (depth + 1)` (36 at eight layers), avoiding a timing-dependent assertion. The pre-fix test failed on the lookup count; after the fix, all eight recursive-wrapping tests and the existing native/virtual filesystem tests passed. This optimization applies to v6/v5, not the legacy v4 API. Its implementation, test and report update form a separate follow-up fix commit.

An additional plugin-style regression subsequently reproduced the same **9,841** lookups when eight views alternated between two classloaders: an isolated copy's `VfsPath` does not satisfy this loader's `instanceof VfsPath` check. Exact-name delegation now also recognizes the ClassGraph provider by its stable class name across classloaders, without relaxing checks for other providers. `isolatedProvidersDoNotMultiplyRecursiveLookups` reads the resource through those alternating views and enforces the same 36-lookup bound. The test failed before the correction and passed afterward, alongside all nine recursive-wrapping tests. This correction is a separate main/v5 follow-up; it changes no public API.

### Additional documentation consistency finding (R26)

The final VFS documentation pass found a misleading eviction promise in the Javadoc, README, tests' explanatory comments, and wiki: eviction was said to permit reclamation of a root and its entry list once callers released it. In fact, `Vfs.openRoots` deliberately retains evicted roots until they or their owning `Vfs` close, so that owner shutdown still releases every root's resources. Documentation now distinguishes cache invalidation from ownership/resource release. A new recursive-wrapper regression verifies that eviction permits a replacement root, leaves both roots usable until owner shutdown, and never transfers ownership of the borrowed source filesystem. No production behavior or API is changed by this clarification.

The same documentation pass aligns the README and VFS wiki reference with provider/storage URI separation, borrowed view handle closure, live native directories, direct file lookups, successful module-size snapshot caching, and recursive wrapping. The porting guide also explains these contracts and the deliberate v4 inheritance/namespace differences.

Main documentation/ownership follow-up: `0e9f19ae8`, v5 `f0bfc8c37`, with a new eviction/borrowed-wrapper regression and full main reactor verification plus targeted JDK 17 verification. Fresh clean builds after the implementation fixes also passed on the minimum supported runtimes: main on JDK 25 (2,665 tests), v5 on JDK 17 (2,694 tests), and v4 on JDK 8 (938 tests), with zero failures/errors. These clean-build counts precede the documentation-only follow-up's additional ownership test and the subsequent R27 module-lookup regressions. The additional recursive-wrapping/ownership verification now comprises nine tests.

Each implementation fix has its own commit and focused regression coverage; documentation-only findings use documentation/source verification instead. The commits below identify implementation milestones and their backports. Publication is gated one commit at a time per branch: all checks for the preceding pushed revision must pass before the next fix is pushed.

One corrective exception was necessary: R11's link-cycle regression exposed its dependency on R08 on Windows, whose native attributes do not provide file keys. The JDK walker then relies on `isSameFile`, so R08 was published to repair that intermediate revision; all three Windows jobs and the full main Java CI passed afterward. The unpublished v5 sequence was reordered to land R08 before R11.

A subsequent R07 test-only correction (`2020082bd` on main, folded into the v5 R07 backport) compares storage URIs against real paths: macOS's `/var` alias and Windows short temporary-directory names need not retain their original spelling after canonicalization. A new symlink-alias regression verifies canonical storage naming, provider URI separation, and readable contents. Production behavior is unchanged.

| Finding | Main commit | v5 backport | Disposition |
| --- | --- | --- | --- |
| R01/R23 | `f05d0e46c` | `6a1411fa4` | Reverse annotation matches use effective member rules; override, hiding, private/static-interface and package-access regressions cover the shared policy. |
| R02 | `c29aaae34` | `02330d207` | Noninheritable ancestor methods excluded from forward queries; declared-member queries remain available. |
| R03 | `6e5218a23` | `5dedbb009` | Lookup-only module discovery tries all layer candidates, with sibling-layer regressions. |
| R04 | `035daf3ef` | `90b6baef6` | Views own and close their handles; ownership, replacement, close-race and error-draining tests added. |
| R05 | `174619d03` | `f30d92d76` | Iterative manifest dependency ordering; 1,200-jar small-stack regression. Additional inspected traversals addressed separately below. |
| R06 | `f6bddbb81` | `f4ecb58df` | Filtering preserves duplicate declaration entries. |
| R07 | `605195bbe` | `a022d9cba` | Provider `toUri()` separated from explicit `toStorageUri()`; internal storage naming, provider round-trips, borrowed/uninstalled views, archive and non-default-filesystem regressions. |
| R08 | `2fccfc0b7` | `43d6841ac` | Actual file identity and both view lifetimes checked; missing files, links, independent archives and native views covered. |
| R09 | `42515f083` | `d420b007a` | Cross-filesystem relativization rejected. |
| R10 | `94ec34b27` | `a5ad79f8a` | Directory filtering is lazy; buffered iterator entries survive stream close under the NIO contract. |
| R11 | `7ac4f9452` | `947039d56` | Native directory namespace and attributes are live; empty directories, mutations, symlinks and walk cycles covered. |
| R12 | `1b86bed5c` | `dff8107e5` | Closed channels detach content, root and owner state. |
| R13 | `c41a5eab5` | `28645f0f7` | Transfers use a bounded independent stream; large offsets, partial writes, EOF and unchanged channel position covered. |
| R14 | `7f5ac01d1` | `5daf64958` | Individual file lookups avoid whole-root enumeration; native mutations and modules that reject enumeration covered. |
| R15 | `d4c412d85` | `00fdb3c9a` | Override ordering uses direct interface edges; 3,000-interface small-stack regression plus existing diamond/member-policy tests. |
| R16 | `52db2b71a` | `b7e74866d` | Fatal VM errors propagate from optional-driver initialization; nonfatal unavailable drivers retain fallback behavior. |
| R17 | `bd84aa660` | `dace0ce7d` | Closed empty scattering reads reject access, with channel-contract regression. |
| R18 | `089d3e5fc` | `116f12bd5` | Source-selection documentation matches explicit module-kind/layer configuration. |
| R19 | `a305cb990` | `f6aaf30c2` | Comments distinguish shared-arena mappings from legacy cleaner-based recovery; v5 text is adapted for its JDK range. |
| R20 | `7f260402e` | `061f54b91` | Unknown module sizes stream through bounded memory and cache on the attribute snapshot; synthetic >2 GiB resource test rejects whole-buffer reads. |
| R21 | `fe75f7eb8` | `18e34dbe8` | Intentional name-based graph identity is documented and tested with separate loader namespaces; no incompatible identity-model rewrite. |
| R22 | `801b3ea49` | `303e2ed49` | Actions pinned to immutable revisions with a validation gate; v5-only workflows included. |
| R24 | `9a88f50d3` | `de88883f6` | Benchmark modes and eviction arguments validated; `none` handled explicitly. |
| R25 | `89da9c3d2` | `807a38472` | Recursive views and independently mounted filesystems receive distinct cache identities; six new tests cover wrapping and lifetimes. |

Additional R05 commits: iterative classpath-container expansion (`93a3cae68`, v5 `5102f7ed0`), module-layer traversal (`7886f8572`, v5 `84efb99a4`), and filesystem directory traversal (`5873edc82`, v5 `6574bafd5`), with the depth and contract tests described above. All three sites identified for further inspection in the original review have been addressed. The directory traversal backport preserves v5's Security Manager handling and passed its full JDK 17 reactor.

The legacy v4 branch receives bugfix-only adaptations, not the new NIO API or changed inheritance/query semantics: fatal-driver initialization propagation (`0b3ca7d83`) and iterative manifest ordering (`099aa255d`). Both passed their JDK 8 tests and all branch CI checks. The porting guide explains the deliberately different v4 member-query behavior.
