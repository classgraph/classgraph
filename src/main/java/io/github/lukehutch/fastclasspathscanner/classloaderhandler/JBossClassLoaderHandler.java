/*
 * This file is part of FastClasspathScanner.
 * 
 * Author: Luke Hutchison
 * 
 * Hosted at: https://github.com/lukehutch/fast-classpath-scanner
 * 
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2016 Luke Hutchison
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
package io.github.lukehutch.fastclasspathscanner.classloaderhandler;

import java.io.File;import java.io.FileFilter;
import java.lang.reflect.Array;

import io.github.lukehutch.fastclasspathscanner.scanner.ClasspathFinder;
import io.github.lukehutch.fastclasspathscanner.utils.LogNode;
import io.github.lukehutch.fastclasspathscanner.utils.ReflectionUtils;

/**
 * Extract classpath entries from the JBoss ClassLoader. See:
 *
 * https://github.com/jboss-modules/jboss-modules/blob/master/src/main/java/org/jboss/modules/ModuleClassLoader.java
 */
public class JBossClassLoaderHandler implements ClassLoaderHandler {
    private static final FileFilter FILE_FILTER = new FileFilter() {
        @Override
        public boolean accept(File pathname) {
            return pathname.isFile() && pathname.exists();
      }
    };
  
    @Override
    public boolean handle(final ClassLoader classloader, final ClasspathFinder classpathFinder, final LogNode log)
            throws Exception {
        for (Class<?> c = classloader.getClass(); c != null; c = c.getSuperclass()) {
            if ("org.jboss.modules.ModuleClassLoader".equals(c.getName())) {
                // type VFSResourceLoader[]
                final Object vfsResourceLoaders = ReflectionUtils.invokeMethod(classloader, "getResourceLoaders");
                if (vfsResourceLoaders != null) {
                    for (int i = 0, n = Array.getLength(vfsResourceLoaders); i < n; i++) {
                        // type VFSResourceLoader
                        final Object resourceLoader = Array.get(vfsResourceLoaders, i);
                        if (resourceLoader != null) {
                            // type VirtualFile
                            final Object root = ReflectionUtils.getFieldVal(resourceLoader, "root");
                            // content directory, usable for war WEB-INF/classes
                            final File file = (File) ReflectionUtils.invokeMethod(root, "getPhysicalFile");
                            
                            if (file != null) {
                                // Find jar files for libraries.
                                classpathFinder.addClasspathElement(file.getAbsolutePath(), log);
                                
                                if (file.getParentFile() != null) {
                                    for (File f: file.getParentFile().listFiles(FILE_FILTER)) {
                                        classpathFinder.addClasspathElement(f.getAbsolutePath(), log);
                                    }
                                }
                            }
                        }
                    }
                }
                return true;
            }
        }
        return false;
    }
}
