# JNI entry points in :core:engine are looked up by name.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
