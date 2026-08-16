package dev.localstream.sender.transport

/** Debug-build-only native invariant probe used by instrumented tests. */
object NativeTestSeam {
    init {
        System.loadLibrary("local_sender")
    }

    external fun run(): Int
}
