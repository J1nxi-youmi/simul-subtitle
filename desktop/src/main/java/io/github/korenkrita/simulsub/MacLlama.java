package io.github.korenkrita.simulsub;
public class MacLlama {
  // the packaged app has it in its resources folder (java.library.path is read once, at JVM start)
  static {
    String res = System.getProperty("compose.application.resources.dir");
    java.io.File f = res == null ? null : new java.io.File(res, "libjpsub_mac.dylib");
    if (f != null && f.isFile()) System.load(f.getAbsolutePath()); else System.loadLibrary("jpsub_mac");
  }
  public static native long nativeLoad(String p, int t);
  public static native byte[] nativeTranslate(long h, String p, int n);
  public static native void nativeCancel(long h);
  public static native void nativeFree(long h);
}
