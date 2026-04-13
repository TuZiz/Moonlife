package ym.moonlife.hook

import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.plugin.Plugin
import java.util.Optional
import java.util.concurrent.atomic.AtomicReference

class ReflectiveMythicMobsHook(private val plugin: Plugin) : MythicMobsHook {
    private val ids = AtomicReference<Set<String>>(emptySet())
    override val available: Boolean get() = plugin.server.pluginManager.isPluginEnabled("MythicMobs")

    override fun reloadIndex() {
        if (!available) {
            ids.set(emptySet())
            return
        }
        val manager = mobManager() ?: return
        val names = runCatching {
            val method = manager.javaClass.methods.firstOrNull {
                it.parameterCount == 0 && (it.name == "getMobNames" || it.name == "getMobTypes" || it.name == "getMobRegistry")
            } ?: return@runCatching emptySet<String>()
            val result = method.invoke(manager)
            when (result) {
                is Collection<*> -> result.mapNotNull { it?.toString() }.toSet()
                is Map<*, *> -> result.keys.mapNotNull { it?.toString() }.toSet()
                else -> emptySet()
            }
        }.getOrDefault(emptySet())
        ids.set(names)
    }

    override fun knownMobIds(): Set<String> = ids.get()

    override fun spawn(mobId: String, location: Location, level: Double): Entity? {
        if (!available) return null
        val manager = mobManager() ?: return null
        val mythicMob = resolveMythicMob(manager, mobId) ?: return null
        val adaptedLocation = adaptLocation(location) ?: return null
        val activeMob = invokeSpawn(mythicMob, adaptedLocation, level) ?: return null
        return activeMobToBukkitEntity(activeMob)
    }

    override fun isMythicMob(entity: Entity): Boolean = internalName(entity) != null

    override fun internalName(entity: Entity): String? {
        if (!available) return null
        val manager = mobManager() ?: return null
        val activeMob = runCatching {
            val method = manager.javaClass.methods.firstOrNull { it.name == "getActiveMob" && it.parameterCount == 1 }
                ?: return null
            unwrapOptional(method.invoke(manager, entity.uniqueId))
        }.getOrNull() ?: return null
        return runCatching {
            val type = activeMob.javaClass.methods.firstOrNull { it.name == "getType" && it.parameterCount == 0 }?.invoke(activeMob)
                ?: activeMob.javaClass.methods.firstOrNull { it.name == "getMobType" && it.parameterCount == 0 }?.invoke(activeMob)
                ?: return null
            type.javaClass.methods.firstOrNull { it.name == "getInternalName" && it.parameterCount == 0 }?.invoke(type)?.toString()
                ?: type.toString()
        }.getOrNull()
    }

    private fun mythicBukkit(): Any? {
        val clazz = runCatching { Class.forName("io.lumine.mythic.bukkit.MythicBukkit") }.getOrNull() ?: return null
        return runCatching {
            clazz.methods.firstOrNull { it.name == "inst" && it.parameterCount == 0 }?.invoke(null)
                ?: clazz.methods.firstOrNull { it.name == "get" && it.parameterCount == 0 }?.invoke(null)
                ?: clazz.methods.firstOrNull { it.name == "getInstance" && it.parameterCount == 0 }?.invoke(null)
        }.getOrNull()
    }

    private fun mobManager(): Any? =
        mythicBukkit()?.let { api ->
            runCatching {
                api.javaClass.methods.firstOrNull { it.name == "getMobManager" && it.parameterCount == 0 }?.invoke(api)
            }.getOrNull()
        }

    private fun resolveMythicMob(manager: Any, mobId: String): Any? = runCatching {
        val method = manager.javaClass.methods.firstOrNull { it.name == "getMythicMob" && it.parameterCount == 1 }
            ?: manager.javaClass.methods.firstOrNull { it.name == "getMobType" && it.parameterCount == 1 }
            ?: return null
        unwrapOptional(method.invoke(manager, mobId))
    }.getOrNull()

    private fun adaptLocation(location: Location): Any? = runCatching {
        val clazz = Class.forName("io.lumine.mythic.bukkit.BukkitAdapter")
        clazz.methods.firstOrNull { it.name == "adapt" && it.parameterCount == 1 && it.parameterTypes[0].isAssignableFrom(Location::class.java) }
            ?.invoke(null, location)
    }.getOrNull()

    private fun invokeSpawn(mythicMob: Any, adaptedLocation: Any, level: Double): Any? = runCatching {
        val methods = mythicMob.javaClass.methods.filter { it.name == "spawn" }
        val method = methods.firstOrNull { it.parameterCount >= 2 } ?: return null
        val args = method.parameterTypes.mapIndexed { index, type ->
            when {
                index == 0 -> adaptedLocation
                type == java.lang.Integer.TYPE || type == Int::class.java -> level.toInt()
                type == java.lang.Double.TYPE || type == Double::class.java -> level
                type == java.lang.Float.TYPE || type == Float::class.java -> level.toFloat()
                else -> null
            }
        }.toTypedArray()
        method.invoke(mythicMob, *args)
    }.getOrNull()

    private fun activeMobToBukkitEntity(activeMob: Any): Entity? = runCatching {
        if (activeMob is Entity) return@runCatching activeMob
        val abstractEntity = activeMob.javaClass.methods.firstOrNull { it.name == "getEntity" && it.parameterCount == 0 }?.invoke(activeMob)
            ?: return null
        abstractEntity.javaClass.methods.firstOrNull { it.name == "getBukkitEntity" && it.parameterCount == 0 }?.invoke(abstractEntity) as? Entity
            ?: abstractEntity as? Entity
    }.getOrNull()

    private fun unwrapOptional(value: Any?): Any? = when (value) {
        is Optional<*> -> value.orElse(null)
        else -> value
    }
}
