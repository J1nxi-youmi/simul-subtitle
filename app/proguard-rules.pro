# sherpa-onnx: the native library finds the Kotlin config/result classes and their fields by name (FindClass,
# GetFieldID) and calls back into them, so nothing in the package may be renamed or removed.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# libjpsub.so exports Java_io_github_korenkrita_simulsub_LlamaTranslator_*: the class name and its native methods must stay.
-keepclasseswithmembernames,includedescriptorclasses class io.github.korenkrita.simulsub.LlamaTranslator {
    native <methods>;
}

# Exception class names are written to session logs (Jev.kt Calls.why: e.javaClass.name); keep them readable.
-keepnames class * extends java.lang.Throwable
