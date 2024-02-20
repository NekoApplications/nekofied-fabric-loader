package net.fabricmc.loader.impl.instrument;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

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
}
