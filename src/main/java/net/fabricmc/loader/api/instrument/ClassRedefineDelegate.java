package net.fabricmc.loader.api.instrument;

import net.fabricmc.loader.impl.launch.knot.KnotClassDelegate;

import java.security.CodeSource;

public interface ClassRedefineDelegate<T extends ClassLoader & KnotClassDelegate.ClassLoaderAccess> {

	T getClassLoader();

	void redefineClass(String name, byte[] b, int off, int len, CodeSource cs);
}
