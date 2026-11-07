package autosubs.core

/** Logging without platform dependencies. The app plugs in android.util.Log. */
fun interface Logger {
    fun log(level: Char, message: String)
}

object CoreLog {
    @JvmStatic
    @Volatile
    var logger: Logger = Logger { _, _ -> }

    @JvmStatic
    fun i(message: String) = logger.log('I', message)

    @JvmStatic
    fun w(message: String) = logger.log('W', message)
}
