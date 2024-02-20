/*
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * This class is copied from byte-buddy-agent, net.bytebuddy.agent Interface ByteBuddyAgent.AttachmentProvider
 */
package icu.takeneko.instrument;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public interface AttachmentProvider {
	ClassLoader BOOTSTRAP_CLASS_LOADER = null;
	/**
	 * The default attachment provider to be used.
	 */
	AttachmentProvider DEFAULT = new Compound(ForModularizedVm.INSTANCE,
			ForJ9Vm.INSTANCE,
			ForStandardToolsJarVm.JVM_ROOT,
			ForStandardToolsJarVm.JDK_ROOT,
			ForStandardToolsJarVm.MACINTOSH,
			ForUserDefinedToolsJar.INSTANCE);

	/**
	 * Attempts the creation of an accessor for a specific JVM's attachment API.
	 *
	 * @return The accessor this attachment provider can supply for the currently running JVM.
	 */
	Accessor attempt();

	/**
	 * An accessor for a JVM's attachment API.
	 */
	interface Accessor {

		/**
		 * The name of the {@code VirtualMachine} class on any OpenJDK or Oracle JDK implementation.
		 */
		String VIRTUAL_MACHINE_TYPE_NAME = "com.sun.tools.attach.VirtualMachine";

		/**
		 * The name of the {@code VirtualMachine} class on IBM J9 VMs.
		 */
		String VIRTUAL_MACHINE_TYPE_NAME_J9 = "com.ibm.tools.attach.VirtualMachine";

		/**
		 * Determines if this accessor is applicable for the currently running JVM.
		 *
		 * @return {@code true} if this accessor is available.
		 */
		boolean isAvailable();

		/**
		 * Returns {@code true} if this accessor prohibits attachment to the same virtual machine in Java 9 and later.
		 *
		 * @return {@code true} if this accessor prohibits attachment to the same virtual machine in Java 9 and later.
		 */
		boolean isExternalAttachmentRequired();

		/**
		 * Returns a {@code VirtualMachine} class. This method must only be called for available accessors.
		 *
		 * @return The virtual machine type.
		 */
		Class<?> getVirtualMachineType();

		/**
		 * Returns a description of a virtual machine class for an external attachment.
		 *
		 * @return A description of the external attachment.
		 */
		ExternalAttachment getExternalAttachment();

		/**
		 * A canonical implementation of an unavailable accessor.
		 */
		enum Unavailable implements Accessor {

			/**
			 * The singleton instance.
			 */
			INSTANCE;

			/**
			 * {@inheritDoc}
			 */
			public boolean isAvailable() {
				return false;
			}

			/**
			 * {@inheritDoc}
			 */
			public boolean isExternalAttachmentRequired() {
				throw new IllegalStateException("Cannot read the virtual machine type for an unavailable accessor");
			}

			/**
			 * {@inheritDoc}
			 */
			public Class<?> getVirtualMachineType() {
				throw new IllegalStateException("Cannot read the virtual machine type for an unavailable accessor");
			}

			/**
			 * {@inheritDoc}
			 */
			public ExternalAttachment getExternalAttachment() {
				throw new IllegalStateException("Cannot read the virtual machine type for an unavailable accessor");
			}
		}

		/**
		 * Describes an external attachment to a Java virtual machine.
		 */
		class ExternalAttachment {

			/**
			 * The fully-qualified binary name of the virtual machine type.
			 */
			private final String virtualMachineType;

			/**
			 * The class path elements required for loading the supplied virtual machine type.
			 */
			private final List<File> classPath;

			/**
			 * Creates an external attachment.
			 *
			 * @param virtualMachineType The fully-qualified binary name of the virtual machine type.
			 * @param classPath          The class path elements required for loading the supplied virtual machine type.
			 */
			public ExternalAttachment(String virtualMachineType, List<File> classPath) {
				this.virtualMachineType = virtualMachineType;
				this.classPath = classPath;
			}

			/**
			 * Returns the fully-qualified binary name of the virtual machine type.
			 *
			 * @return The fully-qualified binary name of the virtual machine type.
			 */
			public String getVirtualMachineType() {
				return virtualMachineType;
			}

			/**
			 * Returns the class path elements required for loading the supplied virtual machine type.
			 *
			 * @return The class path elements required for loading the supplied virtual machine type.
			 */
			public List<File> getClassPath() {
				return classPath;
			}
		}

		/**
		 * A simple implementation of an accessible accessor.
		 */
		abstract class Simple implements Accessor {

			/**
			 * A {@code VirtualMachine} class.
			 */
			protected final Class<?> virtualMachineType;

			/**
			 * Creates a new simple accessor.
			 *
			 * @param virtualMachineType A {@code VirtualMachine} class.
			 */
			protected Simple(Class<?> virtualMachineType) {
				this.virtualMachineType = virtualMachineType;
			}

			/**
			 * <p>
			 * Creates an accessor by reading the process id from the JMX runtime bean and by attempting
			 * to load the {@code com.sun.tools.attach.VirtualMachine} class from the provided class loader.
			 * </p>
			 * <p>
			 * This accessor is supposed to work on any implementation of the OpenJDK or Oracle JDK.
			 * </p>
			 *
			 * @param classLoader A class loader that is capable of loading the virtual machine type.
			 * @param classPath   The class path required to load the virtual machine class.
			 * @return An appropriate accessor.
			 */
			public static Accessor of(ClassLoader classLoader, File... classPath) {
				try {
					return new Simple.WithExternalAttachment(Class.forName(VIRTUAL_MACHINE_TYPE_NAME,
							false,
							classLoader), Arrays.asList(classPath));
				} catch (ClassNotFoundException ignored) {
					return Unavailable.INSTANCE;
				}
			}

			/**
			 * <p>
			 * Creates an accessor by reading the process id from the JMX runtime bean and by attempting
			 * to load the {@code com.ibm.tools.attach.VirtualMachine} class from the provided class loader.
			 * </p>
			 * <p>
			 * This accessor is supposed to work on any implementation of IBM's J9.
			 * </p>
			 *
			 * @return An appropriate accessor.
			 */
			public static Accessor ofJ9() {
				try {
					return new Simple.WithExternalAttachment(ClassLoader.getSystemClassLoader().loadClass(VIRTUAL_MACHINE_TYPE_NAME_J9),
							Collections.<File>emptyList());
				} catch (ClassNotFoundException ignored) {
					return Unavailable.INSTANCE;
				}
			}

			/**
			 * {@inheritDoc}
			 */
			public boolean isAvailable() {
				return true;
			}

			/**
			 * {@inheritDoc}
			 */
			public Class<?> getVirtualMachineType() {
				return virtualMachineType;
			}

			/**
			 * A simple implementation of an accessible accessor that allows for external attachment.
			 */
			protected static class WithExternalAttachment extends Simple {

				/**
				 * The class path required for loading the virtual machine type.
				 */
				private final List<File> classPath;

				/**
				 * Creates a new simple accessor that allows for external attachment.
				 *
				 * @param virtualMachineType The {@code com.sun.tools.attach.VirtualMachine} class.
				 * @param classPath          The class path required for loading the virtual machine type.
				 */
				public WithExternalAttachment(Class<?> virtualMachineType, List<File> classPath) {
					super(virtualMachineType);
					this.classPath = classPath;
				}

				/**
				 * {@inheritDoc}
				 */
				public boolean isExternalAttachmentRequired() {
					return true;
				}

				/**
				 * {@inheritDoc}
				 */
				public ExternalAttachment getExternalAttachment() {
					return new ExternalAttachment(virtualMachineType.getName(), classPath);
				}
			}

			/**
			 * A simple implementation of an accessible accessor that attaches using a virtual machine emulation that does not require external attachment.
			 */
			protected static class WithDirectAttachment extends Simple {

				/**
				 * Creates a new simple accessor that implements direct attachment.
				 *
				 * @param virtualMachineType A {@code VirtualMachine} class.
				 */
				public WithDirectAttachment(Class<?> virtualMachineType) {
					super(virtualMachineType);
				}

				/**
				 * {@inheritDoc}
				 */
				public boolean isExternalAttachmentRequired() {
					return false;
				}

				/**
				 * {@inheritDoc}
				 */
				public ExternalAttachment getExternalAttachment() {
					throw new IllegalStateException("Cannot apply external attachment");
				}
			}
		}
	}

	/**
	 * An attachment provider that locates the attach API directly from the system class loader, as possible since
	 * introducing the Java module system via the {@code jdk.attach} module.
	 */
	enum ForModularizedVm implements AttachmentProvider {

		/**
		 * The singleton instance.
		 */
		INSTANCE;

		/**
		 * {@inheritDoc}
		 */
		public Accessor attempt() {
			return Accessor.Simple.of(ClassLoader.getSystemClassLoader());
		}
	}

	/**
	 * An attachment provider that locates the attach API directly from the system class loader expecting
	 * an IBM J9 VM.
	 */
	enum ForJ9Vm implements AttachmentProvider {

		/**
		 * The singleton instance.
		 */
		INSTANCE;

		/**
		 * {@inheritDoc}
		 */
		public Accessor attempt() {
			return Accessor.Simple.ofJ9();
		}
	}

	/**
	 * An attachment provider that is dependant on the existence of a <i>tools.jar</i> file on the local
	 * file system.
	 */
	enum ForStandardToolsJarVm implements AttachmentProvider {

		/**
		 * An attachment provider that locates the <i>tools.jar</i> from a Java home directory.
		 */
		JVM_ROOT("../lib/tools.jar"),

		/**
		 * An attachment provider that locates the <i>tools.jar</i> from a Java installation directory.
		 * In practice, several virtual machines do not return the JRE's location for the
		 * <i>java.home</i> property against the property's specification.
		 */
		JDK_ROOT("lib/tools.jar"),

		/**
		 * An attachment provider that locates the <i>tools.jar</i> as it is set for several JVM
		 * installations on Apple Macintosh computers.
		 */
		MACINTOSH("../Classes/classes.jar");

		/**
		 * The Java home system property.
		 */
		private static final String JAVA_HOME_PROPERTY = "java.home";

		/**
		 * The path to the <i>tools.jar</i> file, starting from the Java home directory.
		 */
		private final String toolsJarPath;

		/**
		 * Creates a new attachment provider that loads the virtual machine class from the <i>tools.jar</i>.
		 *
		 * @param toolsJarPath The path to the <i>tools.jar</i> file, starting from the Java home directory.
		 */
		ForStandardToolsJarVm(String toolsJarPath) {
			this.toolsJarPath = toolsJarPath;
		}

		/**
		 * {@inheritDoc}
		 */
		//@SuppressFBWarnings(value = "DP_CREATE_CLASSLOADER_INSIDE_DO_PRIVILEGED", justification = "Assuring privilege is explicit user responsibility.")
		public Accessor attempt() {
			File toolsJar = new File(System.getProperty(JAVA_HOME_PROPERTY), toolsJarPath);
			try {
				return toolsJar.isFile() && toolsJar.canRead()
						? Accessor.Simple.of(new URLClassLoader(new URL[]{toolsJar.toURI().toURL()}, BOOTSTRAP_CLASS_LOADER), toolsJar)
						: Accessor.Unavailable.INSTANCE;
			} catch (MalformedURLException exception) {
				throw new IllegalStateException("Could not represent " + toolsJar + " as URL");
			}
		}
	}

	/**
	 * An attachment provider that attempts to locate a {@code tools.jar} from a custom location set via a system property.
	 */
	enum ForUserDefinedToolsJar implements AttachmentProvider {

		/**
		 * The singelton instance.
		 */
		INSTANCE;

		/**
		 * The property being read for locating {@code tools.jar}.
		 */
		public static final String PROPERTY = "net.bytebuddy.agent.toolsjar";


		/**
		 * {@inheritDoc}
		 */
		//@SuppressFBWarnings(value = "DP_CREATE_CLASSLOADER_INSIDE_DO_PRIVILEGED", justification = "Assuring privilege is explicit user responsibility.")
		public Accessor attempt() {
			String location = System.getProperty(PROPERTY);
			if (location == null) {
				return Accessor.Unavailable.INSTANCE;
			} else {
				File toolsJar = new File(location);
				try {
					return Accessor.Simple.of(new URLClassLoader(new URL[]{toolsJar.toURI().toURL()}, BOOTSTRAP_CLASS_LOADER), toolsJar);
				} catch (MalformedURLException exception) {
					throw new IllegalStateException("Could not represent " + toolsJar + " as URL");
				}
			}
		}
	}

	/**
	 * A compound attachment provider that attempts the attachment by delegation to other providers. If
	 * none of the providers of this compound provider is capable of providing a valid accessor, an
	 * non-available accessor is returned.
	 */
	class Compound implements AttachmentProvider {

		/**
		 * A list of attachment providers in the order of their application.
		 */
		private final List<AttachmentProvider> attachmentProviders;

		/**
		 * Creates a new compound attachment provider.
		 *
		 * @param attachmentProvider A list of attachment providers in the order of their application.
		 */
		public Compound(AttachmentProvider... attachmentProvider) {
			this(Arrays.asList(attachmentProvider));
		}

		/**
		 * Creates a new compound attachment provider.
		 *
		 * @param attachmentProviders A list of attachment providers in the order of their application.
		 */
		public Compound(List<? extends AttachmentProvider> attachmentProviders) {
			this.attachmentProviders = new ArrayList<AttachmentProvider>();
			for (AttachmentProvider attachmentProvider : attachmentProviders) {
				if (attachmentProvider instanceof Compound) {
					this.attachmentProviders.addAll(((Compound) attachmentProvider).attachmentProviders);
				} else {
					this.attachmentProviders.add(attachmentProvider);
				}
			}
		}

		/**
		 * {@inheritDoc}
		 */
		public Accessor attempt() {
			for (AttachmentProvider attachmentProvider : attachmentProviders) {
				Accessor accessor = attachmentProvider.attempt();
				if (accessor.isAvailable()) {
					return accessor;
				}
			}
			return Accessor.Unavailable.INSTANCE;
		}
	}
}