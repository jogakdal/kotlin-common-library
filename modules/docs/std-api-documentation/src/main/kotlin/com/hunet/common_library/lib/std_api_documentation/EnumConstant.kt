package com.hunet.common_library.lib.std_api_documentation

import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.type.filter.AnnotationTypeFilter
import org.springframework.stereotype.Component
import java.util.*

@Component
class EnumConstantManager {
    @Value("\${enum.scan.base-package:}")
    lateinit var basePackage: String

    private var data: Map<String, Map<String, Array<out Any>>>? = null
    private var keyValueData: Map<String, Map<String, Map<String, Any>>>? = null

    @PostConstruct
    fun init() {
        initEnumConstant()
    }

    fun findAll(): Map<String, Map<String, Any>> {
        val response: MutableMap<String, LinkedHashMap<String, Any>> = LinkedHashMap()
        data!!.forEach {
            response[it.key] = LinkedHashMap()
            response[it.key]!!.putAll(it.value)
        }

        keyValueData!!.forEach {
            if (response.containsKey(it.key)) {
                it.value.forEach { (t, u) ->
                    response[it.key]!![t] = u
                }
            } else {
                response[it.key] = LinkedHashMap()
                response[it.key]!!.putAll(it.value)
            }
        }
        return response
    }

    fun findByPackageName(packageName: String): Map<String, Any> {
        val response: MutableMap<String, Any> = HashMap()
        response.putAll(data!!.filterKeys { it == packageName }[packageName]!!)
        response.putAll(keyValueData!!.filterKeys { it == packageName }[packageName]!!)
        return response
    }

    private fun initEnumConstant() {
        val packageToScan = basePackage.ifBlank { this::class.java.`package`.name }
        val constantsClasses = collectConstantsAsClass(EnumConstant::class.java, packageToScan)
        val data = mutableMapOf<String, MutableMap<String, Array<out Any>>>()
        val keyValueData = mutableMapOf<String, MutableMap<String, Map<String, Any>>>()

        constantsClasses.forEach {
            val packageName = it.`package`.name
            if (!data.containsKey(packageName)) {
                data[packageName] = mutableMapOf()
            }
            if (!keyValueData.containsKey(packageName)) {
                keyValueData[packageName] = mutableMapOf()
            }

            if (data[packageName]!![it.simpleName] == null) {
                if (it.interfaces.contains(DescriptiveEnum::class.java) ||
                    it.interfaces.contains(ExceptionCode::class.java)) {
                    val tmpMap: MutableMap<String, Any> = LinkedHashMap()

                    it.enumConstants.forEach { enum ->
                        val keyValue = enum as DescriptiveEnum
                        tmpMap[keyValue.value] = keyValue.description
                    }

                    keyValueData[packageName]!![it.simpleName] = tmpMap
                } else {
                    data[packageName]!![it.simpleName] = it.enumConstants
                }
            }
        }

        this.data = Collections.unmodifiableMap(data)
        this.keyValueData = Collections.unmodifiableMap(keyValueData)
    }
}

fun <T : Annotation> collectConstantsAsString(clazz: Class<T>, basePackage: String): List<String?> {
    ClassPathScanningCandidateComponentProvider(false).apply {
        addIncludeFilter(AnnotationTypeFilter(clazz))
        return findCandidateComponents(basePackage).map { beanDef ->
            beanDef.beanClassName
        }
    }
}

fun <T : Annotation> collectConstantsAsClass(clazz: Class<T>, basePackage: String): List<Class<*>> =
    collectConstantsAsString(clazz, basePackage).map {
        Thread.currentThread().contextClassLoader.loadClass(it)
    }
