package org.mule.tooling.tools;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import org.apache.commons.io.FileUtils;
import org.apache.maven.plugin.logging.Log;

public class JarFinder {

	private static final String PREFIX_TEMP_FOLDER_NAME = "checkerJarsTempFolder";

	public static CheckerResults checkJarSnapshotsBuilt(String dir, FilenameFilter filter, Log log,
			ArrayList<String> ignoreJarCheck) throws IOException {
		CheckerResults resultsEachFolder = new CheckerResults();
		CheckerResults totalResult = new CheckerResults();
		File rootFolder = new File(dir);
		if (rootFolder.exists()) {
			resultsEachFolder = checkJarSnapshots(rootFolder.getCanonicalPath(), filter, log, ignoreJarCheck);
			if (resultsEachFolder.hasSnapshots()) {
				totalResult.addCheckResult(resultsEachFolder);
			}
		}
		return totalResult;
	}

	public static CheckerResults checkJarSnapshots(String dir, FilenameFilter filter, Log log,
			ArrayList<String> ignoreJarCheck) throws IOException {
		Collection<String> jarBuildList = JarFinder.getJars(dir, filter);
		CheckerResults results = new CheckerResults();
		if (!jarBuildList.isEmpty()) {
			for (String buildJar : jarBuildList) {
				File file = new File(buildJar);
				if (!checkJarNameInArrayOfIgnoreJars(file.getName(), ignoreJarCheck)) {
					JarFile jarFile = new JarFile(file);
					Enumeration<JarEntry> entries = jarFile.entries();
					if (entries != null) {
						checkJarEntries(entries, log, jarFile, results,ignoreJarCheck);
					}
					jarFile.close();
				}else{
					log.debug("This snapshot is ignored:" + file.getName());
				}
			}
		}
		return results;
	}

	public static boolean checkJarNameInArrayOfIgnoreJars(String jarName, ArrayList<String> ignoreJarCheck) {
		boolean result = false;
		if (ignoreJarCheck != null) {
			for (String jarNameElement : ignoreJarCheck) {
				if (jarName.matches(".*" + jarNameElement + ".*.jar")) {
					result = true;
				}
			}
		}
		return result;
	}

	public static void checkJarEntries(Enumeration<JarEntry> entries, Log log, JarFile jarFile, CheckerResults results, ArrayList<String> ignoreJarCheck)
			throws IOException {
		//log.debug("Checking snapshots jars: " + jarFile.getName());

		Path tempDir = Files.createTempDirectory(PREFIX_TEMP_FOLDER_NAME);
		try {
			// log.debug("Jar:"+jarFile.getName());
			while (entries.hasMoreElements()) {
				boolean isSnapshotEntry = false;
				java.util.jar.JarEntry jarEntry = (java.util.jar.JarEntry) entries.nextElement();
				if (!jarEntry.getName().matches(".*[sS][nN][aA][pP][sS][hH][oO][tT].*.jar")) {
					if (jarEntry.getName().endsWith(".jar")) {
						if (!checkJarNameInArrayOfIgnoreJars(jarEntry.getName(), ignoreJarCheck)) {
							// !jarEntry.getName().contains(java.io.File.separator)
							// Copy jar file in a temp directory.
							JarFinder.copyJarToTempDirectory(tempDir, jarEntry, jarFile);
							isSnapshotEntry = JarFinder.checkSnapshotInPropertiesFile(tempDir, jarEntry, log);
							if (isSnapshotEntry) {
								results.addResult(jarFile.getName(), jarEntry.getName());
							}
						}else{
							log.debug("This snapshot is ignored: "+ jarFile.getName() + " -- [" + jarEntry.getName() + "]");
						}
					}
				} else {
					results.addResult(jarFile.getName(), jarEntry.getName());
				}
			}
		} finally {
			JarFinder.deleteTempDir(tempDir.toFile());
		}
	}


	public static boolean checkSnapshotInPropertiesFile(Path tempDir, JarEntry jarEntry, Log log) throws IOException {
		File fileInside = new File(tempDir.toFile().getCanonicalPath() + java.io.File.separator + jarEntry.getName());
		JarFile jarFileInside = new JarFile(fileInside);
		ArrayList<JarEntry> searchResults = searchForEntry(jarFileInside, "META-INF/maven/.*pom.properties");
		Boolean result = false;
		String artifactId = (String) jarEntry.getName().subSequence(jarEntry.getName().lastIndexOf("/")+1, jarEntry.getName().lastIndexOf("."));
		if (artifactId.indexOf("_") != -1){
			artifactId = (String) jarEntry.getName().subSequence(0, jarEntry.getName().indexOf("_"));
		}
		if (searchResults != null) {
			String pathToResource = searchResults.get(0).getName();
			ZipEntry zipEntry = jarFileInside.getEntry(pathToResource);
			Properties properties = new Properties();
			try (InputStream inputStream = jarFileInside.getInputStream(zipEntry)) {
				properties.load(inputStream);
			}
			jarFileInside.close();
			if (properties.getProperty("version").matches(".*[sS][nN][aA][pP][sS][hH][oO][tT].*") && properties.getProperty("artifactId").matches(".*" + artifactId + ".*")) {
				result = true;
			}
		}
		jarFileInside.close();
		return result;
	}

	public static void deleteTempDir(File file) {
		File[] filesInside = file.listFiles();
		if (filesInside != null) {
			for (File f : filesInside) {
				deleteTempDir(f);
			}
		}
		file.delete();
	}

	public static void copyJarToTempDirectory(Path dirOriginal, JarEntry jarEntry, JarFile jarFile) throws IOException {
		java.io.File f = new java.io.File(
				dirOriginal.toFile().getCanonicalPath() + java.io.File.separator + jarEntry.getName());
		java.io.InputStream is = jarFile.getInputStream(jarEntry);
		FileUtils.copyInputStreamToFile(is, f); // copy the jarEntry to Temp //
												// folder.
		is.close();
	}

	public static final class JarSnapshotFilter implements FilenameFilter {
		@Override
		public boolean accept(File dir, String name) {
			Pattern p = Pattern.compile(".*[sS][nN][aA][pP][sS][hH][oO][tT].*.jar");
			Matcher m = p.matcher(name);
			return name.endsWith(".jar") && m.matches();
		}
	}

	public static final class JarFilter implements FilenameFilter {
		@Override
		public boolean accept(File dir, String name) {
			Pattern p = Pattern.compile(".*.jar");
			Matcher m = p.matcher(name);
			return name.endsWith(".jar") && m.matches();
		}
	}

	public static Collection<String> getJars(String folder, FilenameFilter filter) {
		File fileList = new File(folder);
		Collection<String> jarPaths = new ArrayList<>();
		if (fileList.exists()) {
			List<File> asList = Arrays.asList(fileList.listFiles(filter));
			for (File file : asList) {
				jarPaths.add(file.getPath());
			}
			return jarPaths;
		}
		return jarPaths;
	}

	public static ArrayList<JarEntry> searchForEntry(JarFile jarFile, String searchTermRegex) throws IOException {
		Enumeration<JarEntry> entries = jarFile.entries();
		ArrayList<JarEntry> results = new ArrayList<JarEntry>();
		while (entries.hasMoreElements()) {
			JarEntry jarEntry = entries.nextElement();
			if (jarEntry.getName().matches(searchTermRegex)) {
				results.add(jarEntry);
			}
		}
		if (results.isEmpty()) {
			return null;
		} else {
			return results;
		}
	}
}