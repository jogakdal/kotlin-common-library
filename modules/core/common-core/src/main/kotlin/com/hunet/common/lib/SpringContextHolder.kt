package com.hunet.common.lib

import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationContextAware
import org.springframework.stereotype.Component

/**
 * Spring ApplicationContext를 global하게 보관/조회하기 위한 헬퍼.
 *
 * 사용 시 유의:
 * - 가능한 한 의존성 주입(Constructor / @Autowired)을 우선 사용하고, 정말 DI 가 곤란한
 *   레거시/유틸/코틀린 object 등에서만 사용 권장.
 * - 테스트 환경에서는 Mock / TestApplicationContext로 교체되므로, 테스트 실행 전에 컨텍스트가 초기화되지 않았다면 호출을 삼가야 함.
 * - 다중 스레드에서 읽기 전용으로 안전 (ApplicationContext 자체는 Thread-safe 조회 지원).
 *
 * 주요 메소드:
 * - getBean<T>() : 타입 기반 Bean 조회
 * - getBean(name) : 이름 기반 Bean 조회
 * - getProperty<T>(key) : Environment 프로퍼티 조회 (nullable)
 * - getProperty<T>(key, default) : 기본값 포함 프로퍼티 조회
 *
 * 예시:
 * val myService = SpringContextHolder.getBean<MyService>()
 * val some = SpringContextHolder.getProperty<String>("my.config.key")
 * val flag = SpringContextHolder.getProperty("feature.enabled", false)
 */
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
