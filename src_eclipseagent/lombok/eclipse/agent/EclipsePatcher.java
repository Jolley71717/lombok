package lombok.eclipse.agent;

import java.io.File;
import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.security.ProtectionDomain;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class EclipsePatcher {
	private EclipsePatcher() {}
	
	private static class Patcher implements ClassFileTransformer {
		public byte[] transform(ClassLoader loader, String className,
				Class<?> classBeingRedefined,
				ProtectionDomain protectionDomain, byte[] classfileBuffer)
				throws IllegalClassFormatException {
			
			if ( ECLIPSE_PARSER_CLASS_NAME.equals(className) ) {
				try {
					return runTransform("lombok.eclipse.agent.EclipseParserTransformer", classfileBuffer);
				} catch ( Throwable t ) {
					System.err.println("Wasn't able to patch eclipse's Parser class:");
					t.printStackTrace();
				}
			}
			
			if ( ECLIPSE_CUD_CLASS_NAME.equals(className) ) {
				try {
					return runTransform("lombok.eclipse.agent.EclipseCUDTransformer", classfileBuffer);
				} catch ( Throwable t ) {
					System.err.println("Wasn't able to patch eclipse's CompilationUnitDeclaration class:");
					t.printStackTrace();
				}
			}
			
			return null;
		}
	}
	
	private static byte[] runTransform(String className, byte[] classfileBuffer) throws Exception {
		Class<?> transformerClass = Class.forName(className);
		Constructor<?> constructor = transformerClass.getDeclaredConstructor();
		constructor.setAccessible(true);
		Object instance = constructor.newInstance();
		Method m = transformerClass.getDeclaredMethod("transform", byte[].class);
		m.setAccessible(true);
		return (byte[])m.invoke(instance, classfileBuffer);
	}
	
	static final String ECLIPSE_CUD_CLASS_NAME = "org/eclipse/jdt/internal/compiler/ast/CompilationUnitDeclaration";
	static final String ECLIPSE_PARSER_CLASS_NAME = "org/eclipse/jdt/internal/compiler/parser/Parser";
	
	public static void agentmain(String agentArgs, Instrumentation instrumentation) throws Exception {
		registerPatcher(instrumentation, true);
		addLombokToSearchPaths(instrumentation);
	}
	
	private static void addLombokToSearchPaths(Instrumentation instrumentation) throws Exception {
		String path = findPathOfOurClassloader();
		//On java 1.5, you don't have these methods, so you'll be forced to manually -Xbootclasspath/a them in.
//		instrumentation.appendToSystemClassLoaderSearch(new JarFile(path + "/lombok.jar"));
//		instrumentation.appendToBootstrapClassLoaderSearch(new JarFile(path + "/lombok.eclipse.agent.jar"));
		tryCallMethod(instrumentation, "appendToSystemClassLoaderSearch", path + "/lombok.jar");
		tryCallMethod(instrumentation, "appendToBootstrapClassLoaderSearch", path + "/lombok.eclipse.agent.jar");
	}
	
	private static void tryCallMethod(Object o, String methodName, String path) {
		try {
			Instrumentation.class.getMethod(methodName, JarFile.class).invoke(o, new JarFile(path));
		} catch ( Throwable ignore ) {}
	}
	
	private static String findPathOfOurClassloader() throws Exception {
		URI uri = EclipsePatcher.class.getResource("/" + EclipsePatcher.class.getName().replace('.', '/') + ".class").toURI();
		Pattern p = Pattern.compile("^jar:file:([^\\!]+)\\!.*\\.class$");
		Matcher m = p.matcher(uri.toString());
		if ( !m.matches() ) return ".";
		String rawUri = m.group(1);
		return new File(URLDecoder.decode(rawUri, Charset.defaultCharset().name())).getParent();
	}
	
	public static void premain(String agentArgs, Instrumentation instrumentation) throws Exception {
		registerPatcher(instrumentation, false);
		addLombokToSearchPaths(instrumentation);
	}
	
	private static void registerPatcher(Instrumentation instrumentation, boolean transformExisting) throws IOException {
		instrumentation.addTransformer(new Patcher()/*, true*/);
		
		if ( transformExisting ) for ( Class<?> c : instrumentation.getAllLoadedClasses() ) {
			if ( c.getName().equals(ECLIPSE_PARSER_CLASS_NAME) || c.getName().equals(ECLIPSE_CUD_CLASS_NAME) ) {
				try {
					//instrumentation.retransformClasses(c); - //not in java 1.5.
					Instrumentation.class.getMethod("retransformClasses", Class[].class).invoke(instrumentation,
							new Object[] { new Class[] {c }});
				} catch ( InvocationTargetException e ) {
					throw new UnsupportedOperationException(
							"The eclipse parser class is already loaded and cannot be modified. " +
							"You'll have to restart eclipse in order to use Lombok in eclipse.");
				} catch ( Throwable t ) {
					throw new UnsupportedOperationException(
							"This appears to be a java 1.5 instance, which cannot reload already loaded classes. " +
					"You'll have to restart eclipse in order to use Lombok in eclipse.");
				}
			}
		}
	}
}
