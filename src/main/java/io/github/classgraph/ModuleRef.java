/*
 * This file is part of ClassGraph.
 *
 * Author: Luke Hutchison
 *
 * Hosted at: https://github.com/classgraph/classgraph
 *
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2018 Luke Hutchison
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without
 * limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO
 * EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE
 * OR OTHER DEALINGS IN THE SOFTWARE.
 */
package io.github.classgraph;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import io.github.classgraph.utils.JarUtils;
import io.github.classgraph.utils.ReflectionUtils;

/** A ModuleReference proxy, written using reflection to preserve backwards compatibility with JDK 7 and 8. */
public class ModuleRef implements Comparable<ModuleRef> {
    /** The name of the module. */
    private final String name;

    /** The ModuleReference for the module. */
    private final Object reference;

    /** The ModuleLayer for the module. */
    private final Object layer;

    /** The ModuleDescriptor for the module. */
    private final Object descriptor;

    /** The packages in the module. */
    private final List<String> packages;

    /** The location URI for the module (may be null). */
    private final URI location;

    /** The location URI for the module, as a cached string (may be null). */
    private String locationStr;

    /** A file formed from the location URI. The file will not exist if the location URI is a jrt:/ URI. */
    private File locationFile;

    /** The raw module version, or null if none. */
    private String rawVersion;

    /** The ClassLoader that loads classes in the module. May be null, to represent the bootstrap classloader. */
    private final ClassLoader classLoader;

    /**
     * @param moduleReference
     *            The module reference, of JPMS type ModuleReference.
     * @param moduleLayer
     *            The module layer, of JPMS type ModuleLayer
     */
    public ModuleRef(final Object moduleReference, final Object moduleLayer) {
        if (moduleReference == null) {
            throw new IllegalArgumentException("moduleReference cannot be null");
        }
        if (moduleLayer == null) {
            throw new IllegalArgumentException("moduleLayer cannot be null");
        }
        this.reference = moduleReference;
        this.layer = moduleLayer;

        this.descriptor = ReflectionUtils.invokeMethod(moduleReference, "descriptor", /* throwException = */ true);
        if (this.descriptor == null) {
            // Should not happen
            throw new IllegalArgumentException("moduleReference.descriptor() should not return null");
        }
        final String moduleName = (String) ReflectionUtils.invokeMethod(this.descriptor, "name",
                /* throwException = */ true);
        this.name = moduleName == null ? "" : moduleName;
        @SuppressWarnings("unchecked")
        final Set<String> packages = (Set<String>) ReflectionUtils.invokeMethod(this.descriptor, "packages",
                /* throwException = */ true);
        if (packages == null) {
            // Should not happen
            throw new IllegalArgumentException("moduleReference.descriptor().packages() should not return null");
        }
        this.packages = new ArrayList<>(packages);
        Collections.sort(this.packages);
        final Object optionalRawVersion = ReflectionUtils.invokeMethod(this.descriptor, "rawVersion",
                /* throwException = */ true);
        if (optionalRawVersion != null) {
            final Boolean isPresent = (Boolean) ReflectionUtils.invokeMethod(optionalRawVersion, "isPresent",
                    /* throwException = */ true);
            if (isPresent != null && isPresent) {
                this.rawVersion = (String) ReflectionUtils.invokeMethod(optionalRawVersion, "get",
                        /* throwException = */ true);
            }
        }
        final Object moduleLocationOptional = ReflectionUtils.invokeMethod(moduleReference, "location",
                /* throwException = */ true);
        if (moduleLocationOptional == null) {
            // Should not happen
            throw new IllegalArgumentException("moduleReference.location() should not return null");
        }
        final Object moduleLocationIsPresent = ReflectionUtils.invokeMethod(moduleLocationOptional, "isPresent",
                /* throwException = */ true);
        if (moduleLocationIsPresent == null) {
            // Should not happen
            throw new IllegalArgumentException("moduleReference.location().isPresent() should not return null");
        }
        if ((Boolean) moduleLocationIsPresent) {
            this.location = (URI) ReflectionUtils.invokeMethod(moduleLocationOptional, "get",
                    /* throwException = */ true);
            if (this.location == null) {
                // Should not happen
                throw new IllegalArgumentException("moduleReference.location().get() should not return null");
            }
        } else {
            this.location = null;
        }

        // Find the classloader for the module
        this.classLoader = (ClassLoader) ReflectionUtils.invokeMethod(moduleLayer, "findLoader", String.class,
                this.name, /* throwException = */ true);
    }

    /** @return The module name, i.e. {@code getReference().descriptor().name()}. */
    public String getName() {
        return name;
    }

    /** @return The module reference (of JPMS type ModuleReference). */
    public Object getReference() {
        return reference;
    }

    /** @return The module layer (of JPMS type ModuleLayer). */
    public Object getLayer() {
        return layer;
    }

    /** @return The module descriptor, i.e. {@code getReference().descriptor()} (of JPMS type ModuleDescriptor). */
    public Object getDescriptor() {
        return descriptor;
    }

    /** @return The list of packages in the module. (Does not include non-package directories.) */
    public List<String> getPackages() {
        return packages;
    }

    /**
     * @return The module location, i.e. {@code getReference().location()}. Returns null for modules that do not
     *         have a location.
     */
    public URI getLocation() {
        return location;
    }

    /**
     * @return The module location as a string, i.e. {@code getReference().location().toString()}. Returns null for
     *         modules that do not have a location.
     */
    public String getLocationStr() {
        if (locationStr == null && location != null) {
            locationStr = location.toString();
        }
        return locationStr;
    }

    /**
     * @return The module location as a File, i.e. {@code new File(getReference().location())}. Returns null for
     *         modules that do not have a location, or for system ("jrt:/") modules.
     */
    public File getLocationFile() {
        if (locationFile == null && location != null) {
            if (!isSystemModule()) {
                try {
                    locationFile = new File(location);
                } catch (final Exception e) {
                    return null;
                }
            } else {
                return null;
            }
        }
        return locationFile;
    }

    /**
     * Get the raw version string of the module, or null if the module did not provide one.
     * 
     * @return The raw version of the module, obtained by {@code ModuleReference#rawVersion().orElse(null)}.
     */
    public String getRawVersion() {
        return rawVersion;
    }

    /**
     * @return true if this module's location is a non-"file:/" ("jrt:/") URI, or if it has no location URI, or if
     *         it uses the (null) bootstrap ClassLoader, or if the module name starts with a system prefix ("java.",
     *         "jre.", etc.).
     */
    public boolean isSystemModule() {
        if (location == null) {
            return true;
        }
        final String scheme = location.getScheme();
        if (scheme != null && scheme.equalsIgnoreCase("jrt")) {
            return true;
        }
        if (JarUtils.isInSystemPackageOrModule(name)) {
            return true;
        }
        return false;
    }

    /**
     * @return The classloader for the module, i.e.
     *         {@code moduleLayer.findLoader(getReference().descriptor().name())}.
     */
    public ClassLoader getClassLoader() {
        return classLoader;
    }

    @Override
    public boolean equals(final Object obj) {
        if (!(obj instanceof ModuleRef)) {
            return false;
        }
        final ModuleRef mr = (ModuleRef) obj;
        return mr.reference.equals(this.reference) && mr.layer.equals(this.layer);
    }

    @Override
    public int hashCode() {
        return reference.hashCode() * layer.hashCode();
    }

    @Override
    public String toString() {
        return reference.toString();
    }

    @Override
    public int compareTo(final ModuleRef o) {
        final int diff = this.name.compareTo(o.name);
        return diff != 0 ? diff : this.hashCode() - o.hashCode();
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Open the module, returning a {@link ModuleReaderProxy}.
     * 
     * @return A {@link ModuleReaderProxy} for the module.
     * @throws IOException
     *             If the module cannot be opened.
     */
    public ModuleReaderProxy open() throws IOException {
        return new ModuleReaderProxy(this);
    }
}
