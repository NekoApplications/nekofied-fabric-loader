package net.fabricmc.loader.impl.instrument;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.security.CodeSource;
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
			String name = ManagementFactory.getRuntimeMXBean().getName();
			String pid = name.split("@")[0];
			Log.info(LogCategory.KNOT, "Attaching agent to vm");
			AttachmentProvider.Accessor accessor = AttachmentProvider.DEFAULT.attempt();
			if (!accessor.isAvailable()) {
				throw new RuntimeException("No Attachment available.");
			}
			if (accessor.isExternalAttachmentRequired()){
				throw new RuntimeException("Require external attatchment");
			}
			Class<?> virtualMachineType = accessor.getVirtualMachineType();

			try {
				Class<?> cls = Class.forName("sun.tools.attach.HotSpotVirtualMachine");
				Field field = cls.getDeclaredField("ALLOW_ATTACH_SELF");
				field.setAccessible(true);
				Field modifiersField = Field.class.getDeclaredField("modifiers");
				modifiersField.setInt(field, field.getModifiers() & ~Modifier.FINAL);
				field.setBoolean(null, true);
			} catch (Exception e) {
				Log.warn(LogCategory.KNOT, "Could not modify ALLOW_ATTACH_SELF to true.", e);
			}

			Object virtualMachineInstance = virtualMachineType
					.getMethod("attach", String.class)
					.invoke(null, pid);
			instrumentationFuture = new CompletableFuture<>();
			virtualMachineType.getMethod("loadAgent", String.class, String.class)
					.invoke(virtualMachineInstance, extractJar(), null);
			return instrumentationFuture.get(10000, TimeUnit.MILLISECONDS);
		} catch (TimeoutException te) {
			throw new RuntimeException("Timeout while waiting agent to attach.", te);
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	public String extractJar() {
		String clazzFilePath = LoaderUtil.getClassFileName(AgentProviderMain.class.getName());
		try {
			InputStream is = this.getClass().getClassLoader().getResourceAsStream(clazzFilePath);
			File jarFile = new File(FabricLoaderImpl.CACHE_DIR_NAME).toPath().resolve("fabric_agent_" + System.currentTimeMillis() + ".jar").toFile();
			if (jarFile.exists()) {
				jarFile.delete();
			}
			jarFile.createNewFile();
			jarFile.deleteOnExit();
			FileOutputStream fileOS = new FileOutputStream(jarFile);
			Manifest mf = new Manifest();
			Attributes a = mf.getMainAttributes();
			a.put(Attributes.Name.MANIFEST_VERSION, "1.0");
			a.put("Agent-Class", "net.fabricmc.loader.impl.instrument.AgentProviderMain");
			a.put("Can-Redefine-Classes", "true");
			a.put("Can-Retransform-Classes", "true");
			JarOutputStream jarOutputStream = new JarOutputStream(fileOS, mf);
			ZipEntry classEntry = new ZipEntry(clazzFilePath);
			jarOutputStream.putNextEntry(classEntry);
			while (is.available() != 0) {
				jarOutputStream.write(is.read());
			}
			jarOutputStream.close();
			is.close();
			return jarFile.getPath();
		} catch (IOException e) {
			throw new RuntimeException(e);
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
