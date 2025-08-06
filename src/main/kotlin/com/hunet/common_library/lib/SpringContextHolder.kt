package com.hunet.common_library.lib

import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationContextAware
import org.springframework.stereotype.Component

@Component
object SpringContextHolder : ApplicationContextAware {
    lateinit var context: ApplicationContext
        private set

    override fun setApplicationContext(applicationContext: ApplicationContext) {
        context = applicationContext
    }

    inline fun <reified T> getBean(): T = context.getBean(T::class.java)
    fun getBean(name: String): Any =  context.getBean(name)
    inline fun <reified T: Any> getProperty(name: String) = context.environment.getProperty(name, T::class.java)
    inline fun <reified T: Any> getProperty(name: String, defaultValue: T): T =
        context.environment.getProperty(name, T::class.java, defaultValue)
}
