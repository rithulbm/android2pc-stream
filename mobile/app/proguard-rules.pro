# JNI entry points are resolved by their exported names.
-keepclasseswithmembernames class * {
    native <methods>;
}

# ZXing is used directly for offline QR decoding; no reflection keep rules are needed.

