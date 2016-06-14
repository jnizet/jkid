package ru.yole.jkid.deserialization

import ru.yole.jkid.asJavaClass
import ru.yole.jkid.isPrimitive
import java.io.Reader
import java.io.StringReader
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.util.*
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.jvm.javaType

inline fun <reified T: Any> deserialize(json: String): T {
    return deserialize(StringReader(json))
}

inline fun <reified T: Any> deserialize(json: Reader): T {
    return deserialize(json, T::class)
}

fun <T: Any> deserialize(json: Reader, targetClass: KClass<T>): T {
    val seed = ObjectSeed(targetClass, ClassInfoCache())
    val stack = Stack<Seed>()
    stack.push(seed)

    val callback = object : JsonParseCallback {
        override fun enterObject(propertyName: String) {
            val compositeSeed = stack.peek().createCompositeProperty(propertyName)
            stack.push(compositeSeed)
        }

        override fun leaveObject() {
            stack.pop()
        }

        override fun enterArray(propertyName: String) {
            enterObject(propertyName)
        }

        override fun leaveArray() {
            leaveObject()
        }

        override fun visitValue(propertyName: String, value: Any?) {
            stack.peek().setSimpleProperty(propertyName, value)
        }
    }
    Parser(json, callback).parse()
    return seed.spawn()
}


interface Seed {

    val classInfoCache: ClassInfoCache

    fun setSimpleProperty(propertyName: String, value: Any?)

    fun createCompositeProperty(propertyName: String): Seed

    fun spawn(): Any?
}

fun Seed.createSeedForType(paramType: Type): Seed {
    val paramClass = paramType.asJavaClass()

    if (Collection::class.java.isAssignableFrom(paramClass)) {
        val parameterizedType = paramType as? ParameterizedType
                ?: throw UnsupportedOperationException("Unsupported parameter type $this")

        val elementType = parameterizedType.actualTypeArguments.single()
        if (elementType.isPrimitive()) {
            return ValueCollectionSeed(classInfoCache)
        }
        return ObjectCollectionSeed(elementType, classInfoCache)
    }
    if (Map::class.java.isAssignableFrom(paramClass)) {
        val parameterizedType = paramType as? ParameterizedType
                ?: throw UnsupportedOperationException("Unsupported parameter type $this")

        val elementType = parameterizedType.actualTypeArguments[1]
        return MapSeed(elementType, classInfoCache)
    }
    return ObjectSeed(paramClass.kotlin, classInfoCache)
}


class ObjectSeed<out T: Any>(
        targetClass: KClass<T>,
        override val classInfoCache: ClassInfoCache
) : Seed {

    private val classInfo: ClassInfo<T> = classInfoCache[targetClass]

    private val valueArguments = mutableMapOf<KParameter, Any?>()
    private val seedArguments = mutableMapOf<KParameter, Seed>()

    private val arguments: Map<KParameter, Any?>
        get() = valueArguments + seedArguments.mapValues { it.value.spawn() }

    override fun setSimpleProperty(propertyName: String, value: Any?) {
        val param = classInfo.getConstructorParameter(propertyName)
        valueArguments[param] = classInfo.deserializeConstructorArgument(param, value)
    }

    override fun createCompositeProperty(propertyName: String): Seed {
        val param = classInfo.getConstructorParameter(propertyName)
        return createSeedForType(param.type.javaType).apply { seedArguments[param] = this }
    }

    override fun spawn(): T = classInfo.newInstance(arguments)
}

class ObjectCollectionSeed(
        val elementType: Type,
        override val classInfoCache: ClassInfoCache
) : Seed {
    private val elements = mutableListOf<Seed>()

    override fun setSimpleProperty(propertyName: String, value: Any?) {
        throw JKidException("Found primitive value in collection of object types")
    }

    override fun createCompositeProperty(propertyName: String) =
            createSeedForType(elementType).apply { elements.add(this) }

    override fun spawn(): Collection<*> = elements.map { it.spawn() }
}

class ValueCollectionSeed(override val classInfoCache: ClassInfoCache) : Seed {
    private val elements = mutableListOf<Any?>()

    override fun setSimpleProperty(propertyName: String, value: Any?) {
        elements.add(value)
    }

    override fun createCompositeProperty(propertyName: String): Seed {
        throw JKidException("Found object value in collection of primitive types")
    }

    override fun spawn() = elements
}

class MapSeed(val elementType: Type, override val classInfoCache: ClassInfoCache) : Seed {
    private val valueMap = mutableMapOf<String, Any?>()
    private val seedMap = mutableMapOf<String, Seed>()

    override fun setSimpleProperty(propertyName: String, value: Any?) {
        valueMap[propertyName] = value
    }

    override fun createCompositeProperty(propertyName: String) =
            createSeedForType(elementType).apply { seedMap[propertyName] = this }

    override fun spawn(): Map<String, Any?> =
            valueMap + seedMap.mapValues { it.value.spawn() }
}