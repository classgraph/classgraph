package io.github.lukehutch.fastclasspathscanner.classloaderhandler;

import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;

import io.github.lukehutch.fastclasspathscanner.scanner.ClasspathFinder;
import io.github.lukehutch.fastclasspathscanner.utils.LogNode;
import io.github.lukehutch.fastclasspathscanner.utils.ReflectionUtils;

/**
 * Custom Class Loader Handler for OSGi Felix ClassLoader
 *
 * The handler adds the bundle jar and all assocaited Bundle-Claspath jars into the {@link ClasspathFinder} scan
 * classpath.
 *
 * @author elrufaie
 */
public class FelixClassLoaderHandler implements ClassLoaderHandler {

	private final String JAR_FILE_PREFIX = "jar:";

	private final String JAR_FILE_DELIM = "!/";

	private static final String BY_COMMA = ",";

	@Override
	public boolean handle(final ClassLoader classloader, final ClasspathFinder classpathFinder, final LogNode log)
			throws Exception {

		for (Class<?> c = classloader.getClass(); c != null; c = c.getSuperclass()) {
			if ("org.apache.felix.framework.BundleWiringImpl$BundleClassLoaderJava5".equals(c.getName())) {
				// Type: BundleImpl
				final Object m_wiring = ReflectionUtils.getFieldVal(classloader, "m_wiring");
				// Type: Bundle
				final Object bundle = ReflectionUtils.invokeMethod(m_wiring, "getBundle");

				@SuppressWarnings("unchecked")
				final Map<String, Map<?, ?>> bundleHeaders = (Map<String, Map<?, ?>>) ReflectionUtils
						.getFieldVal(bundle, "m_cachedHeaders");

				final Object bundlefile = ReflectionUtils.getFieldVal(bundle, "m_archive");

				// Should be a valid jar (should start with "file:/" and end with ".jar")
				final Object bundlefileLocation = ReflectionUtils.getFieldVal(bundlefile, "m_originalLocation");

				if (bundleHeaders != null && !bundleHeaders.isEmpty()) {
					// Add bundleFile
					final String bundleFile = (String) bundlefileLocation;
					classpathFinder.addClasspathElement(bundleFile, log);

					// Should find one element only
					final Iterator<Entry<String, Map<?, ?>>> it = bundleHeaders.entrySet().iterator();
					if (it.hasNext()) {
						final Entry<String, Map<?, ?>> pair = it.next();
						final Map<?, ?> stringMap = pair.getValue();
						// Type: String
						final String classpath = (String) stringMap.get("Bundle-Classpath");
						// If we have multiple jars in classpath, add them all
						if (classpath != null) {
							final String[] splitJars = classpath.split(BY_COMMA);
							for (int i = 0; i < splitJars.length; i++) {
								// Should be something like this:
								// jar:file:/path/myBundleFile.jar!/v4-sdk-schema-1.4.0-develop.v12.jar
								if (!splitJars[i].isEmpty()) {
									final String jarPath = JAR_FILE_PREFIX + bundleFile + JAR_FILE_DELIM
											+ splitJars[i];
									classpathFinder.addClasspathElement(jarPath, log);
								}
							}
						}
					}
					return true;
				}
			}
		}
		return false;
	}
}