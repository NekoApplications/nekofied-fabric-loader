package net.fabricmc.loader.impl.instrument;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;

public class AgentProviderMain {
	private static void accept(Instrumentation inst) {
		try {
			Class<ClassRedefineDelegateImpl> clazz = ClassRedefineDelegateImpl.class;
			Field future = clazz.getDeclaredField("instrumentationFuture");
			future.setAccessible(true);
			CompletableFuture<Instrumentation> instrumentationFuture = (CompletableFuture<Instrumentation>) future.get(null);
			instrumentationFuture.complete(inst);
		}catch (Throwable e){
			throw new RuntimeException("Unable to install instrument",e);
		}
    }

	public static void agentmain(String args,Instrumentation inst) throws Exception {
		accept(inst);
	}

	private static String readPidFromRes(String[] args) throws IOException {
		InputStream is = AgentProviderMain.class.getResourceAsStream("pid");
		if (is != null){
			byte[] bytes = new byte[1024];
			int len = is.read(bytes);
            return new String(bytes, 0, len);
		}
		return args[0];
	}

	public static void main(String[] args) throws IOException, InterruptedException {
		String pid = readPidFromRes(args);
		if (pid.isEmpty())throw new RuntimeException("No pid provided!");
		try {
			Class<?> virtualMachineType = Class.forName(args[2]);
			Object virtualMachineInstance = virtualMachineType
					.getMethod("attach", String.class)
					.invoke(null, pid);
			virtualMachineType.getMethod("loadAgent", String.class, String.class)
					.invoke(virtualMachineInstance, args[1], null);
//			VirtualMachine vm = VirtualMachine.attach(pid);
//			vm.loadAgent(args[2]);
		}catch (Exception e){
			File errorDumpFile = new File("C:/errors/errdump.txt");
			Files.createDirectories(errorDumpFile.toPath().getParent());
			if (errorDumpFile.exists()){
				errorDumpFile.delete();
			}
			errorDumpFile.createNewFile();
			PrintStream ps = new PrintStream(errorDumpFile);
			e.printStackTrace(ps);
			e.printStackTrace(System.out);
			System.exit(2);
		}
		System.exit(0);
	}
}
