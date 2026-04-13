package ym.moonlife.util

object ReflectionUtil {
    fun classExists(name: String): Boolean = runCatching {
        Class.forName(name, false, Thread.currentThread().contextClassLoader)
        true
    }.getOrDefault(false)

    fun invokeNoArgs(target: Any, name: String): Any? =
        target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }?.invoke(target)

    fun invokeStaticNoArgs(className: String, name: String): Any? = runCatching {
        val clazz = Class.forName(className)
        clazz.methods.firstOrNull { it.name == name && it.parameterCount == 0 }?.invoke(null)
    }.getOrNull()
}
