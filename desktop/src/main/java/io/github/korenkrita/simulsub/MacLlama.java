package io.github.korenkrita.simulsub;
public class MacLlama {
  static { System.loadLibrary("jpsub_mac"); }
  public static native long nativeLoad(String p, int t);
  public static native byte[] nativeTranslate(long h, String p, int n);
  public static native void nativeCancel(long h);
  public static native void nativeFree(long h);
}
