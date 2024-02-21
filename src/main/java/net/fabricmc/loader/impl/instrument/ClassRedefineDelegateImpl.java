package net.fabricmc.loader.impl.instrument;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.CodeSource;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

import icu.takeneko.instrument.AttachmentProvider;

import net.fabricmc.loader.api.instrument.ClassRedefineDelegate;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.launch.knot.KnotClassDelegate;
import net.fabricmc.loader.impl.util.LoaderUtil;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;

public class ClassRedefineDelegateImpl<T extends ClassLoader & KnotClassDelegate.ClassLoaderAccess> implements ClassRedefineDelegate<T> {
	private final T classLoader;
	private final Instrumentation inst;

	private static Future<Instrumentation> instrumentationFuture;

	public ClassRedefineDelegateImpl(T classLoader) {
		this.classLoader = classLoader;
		inst = install();
	}

	public Instrumentation install() {
		try {
			clearAgentJars();
			String name = ManagementFactory.getRuntimeMXBean().getName();
			String pid = name.split("@")[0];
			Log.info(LogCategory.KNOT, "Attaching agent to vm");
			AttachmentProvider.Accessor accessor = AttachmentProvider.DEFAULT.attempt();
			if (!accessor.isAvailable()) {
				throw new RuntimeException("No Attachment available.");
			}
			String jarFilePath = extractJar(pid);
//			if (accessor.isExternalAttachmentRequired()){
//				throw new RuntimeException("Require external attatchment");
//			}
			instrumentationFuture = new CompletableFuture<>();
			if (accessor.isExternalAttachmentRequired()) {
				externalAttach(accessor.getExternalAttachment(), pid, new File(jarFilePath), new File(jarFilePath));
			} else {
				attachSelf(accessor, pid, jarFilePath);
			}
			return instrumentationFuture.get(10000, TimeUnit.MILLISECONDS);
		} catch (TimeoutException te) {
			throw new RuntimeException("Timeout while waiting agent to attach.", te);
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private static void externalAttach(AttachmentProvider.Accessor.ExternalAttachment externalAttachment,
									   String processId,
									   File agent,
									   File attachmentJar) throws Exception {
		try {
			StringBuilder classPath = new StringBuilder().append(attachmentJar.getCanonicalPath());
			for (File jar : externalAttachment.getClassPath()) {
				classPath.append(File.pathSeparatorChar).append(jar.getCanonicalPath());
			}
			String[] command = new String[]{System.getProperty("java.home")
					+ File.separatorChar + "bin"
					+ File.separatorChar + (System.getProperty("os.name", "").toLowerCase(Locale.US).contains("windows") ? "java.exe" : "java"),
					"-cp",
					classPath.toString(),
					"net.fabricmc.loader.impl.instrument.AgentProviderMain",
					processId,
					agent.getCanonicalPath(),
					externalAttachment.getVirtualMachineType()};
			System.out.println(Arrays.deepToString(command));
			Process process = new ProcessBuilder(command).start();
			BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
			String line = reader.readLine();
			while (process.isAlive()){
				System.out.println(line);
				line = reader.readLine();
			}
			if (process.exitValue() != 0) {
				throw new IllegalStateException("Could not self-attach to current VM using external process");
			}
		} finally {
			if (attachmentJar != null) {
				if (!attachmentJar.delete()) {
					attachmentJar.deleteOnExit();
				}
			}
		}
	}

	private void attachSelf(AttachmentProvider.Accessor accessor, String pid, String jarFilePath) throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
		Class<?> virtualMachineType = accessor.getVirtualMachineType();
		Object virtualMachineInstance = virtualMachineType
				.getMethod("attach", String.class)
				.invoke(null, pid);
		virtualMachineType.getMethod("loadAgent", String.class, String.class)
				.invoke(virtualMachineInstance, jarFilePath, null);
	}

	private String extractJar(String pid) {
		String clazzFilePath = LoaderUtil.getClassFileName(AgentProviderMain.class.getName());
		try {
			InputStream is = this.getClass().getClassLoader().getResourceAsStream(clazzFilePath);
			File jarFile = new File(FabricLoaderImpl.CACHE_DIR_NAME).toPath().resolve("fabric_agent_" + System.currentTimeMillis() + ".jar").toFile();
			Files.createDirectories(jarFile.toPath().getParent());
			if (jarFile.exists()) {
				jarFile.delete();
			}
			jarFile.createNewFile();
			jarFile.deleteOnExit();
			FileOutputStream fileOS = new FileOutputStream(jarFile);
			Manifest mf = new Manifest();
			Attributes a = mf.getMainAttributes();
			a.put(Attributes.Name.MANIFEST_VERSION, "1.0");
			a.put(new Attributes.Name("Agent-Class"), "net.fabricmc.loader.impl.instrument.AgentProviderMain");
			a.put(new Attributes.Name("Can-Redefine-Classes"), "true");
			a.put(new Attributes.Name("Can-Retransform-Classes"), "true");
			JarOutputStream jarOutputStream = new JarOutputStream(fileOS, mf);
			ZipEntry classEntry = new ZipEntry(clazzFilePath);
			jarOutputStream.putNextEntry(classEntry);
			while (is.available() != 0) {
				jarOutputStream.write(is.read());
			}
			is.close();
			jarOutputStream.putNextEntry(new ZipEntry("pid"));
			jarOutputStream.write(pid.getBytes(StandardCharsets.UTF_8));
			clazzFilePath = LoaderUtil.getClassFileName(AttachmentProvider.class.getName());
			jarOutputStream.putNextEntry(new ZipEntry(clazzFilePath));
			is = this.getClass().getClassLoader().getResourceAsStream(clazzFilePath);
			while (is.available() != 0) {
				jarOutputStream.write(is.read());
			}
			jarOutputStream.close();

			return jarFile.getPath();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private void clearAgentJars(){
		File cacheDir = new File(FabricLoaderImpl.CACHE_DIR_NAME);
		File[] files = cacheDir.listFiles((dir, name) -> name.contains("fabric_agent_") && dir.getPath().endsWith(".fabric"));
		if (files == null)return;
		for (File file : files) {
			if (file.exists()){
				file.delete();
			}
		}
	}

	@Override
	public T getClassLoader() {
		return classLoader;
	}

	@Override
	public void redefineClass(String name, byte[] b, int off, int len, CodeSource cs) {
		try {
			inst.redefineClasses(new ClassDefinition(Class.forName(name), b));
		} catch (Exception e) {
			throw new RuntimeException("Unable to redefine class " + name, e);
		}
	}

	public static <C extends ClassLoader & KnotClassDelegate.ClassLoaderAccess> ClassRedefineDelegate<C> attatchDelegate(C classLoader) {
		return new ClassRedefineDelegateImpl<>(classLoader);
	}
}
