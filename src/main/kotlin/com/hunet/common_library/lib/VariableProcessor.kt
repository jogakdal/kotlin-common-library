package com.hunet.common_library.lib

import org.springframework.stereotype.Component

/**
 * Registry of variable resolver functions.
 *
 * 각 엔트리는 템플릿 내 토큰 이름을 키로,
 * 파라미터 리스트(List<Any?>)를 받아 실제 치환값을 리턴하는 함수를 값으로 가집니다.
 *
 * 비즈니스 모듈에서는 이 인터페이스를 구현한 클래스 하나만
 * @Component로 등록하면, VariableProcessor가 자동으로 주입받아 사용합니다.
 *
 * 예:
 * ```
 * @Component
 * class BusinessVariableResolvers(
 *     private val nodeService: NodeService,
 *     private val userService: UserService
 * ) : VariableResolverRegistry {
 *     override val resolvers = mapOf(
 *         // 파라미터 없이 동작
 *         "node_count" to { _ -> nodeService.getNodeCount() },
 *         // 첫 번째 파라미터를 이름으로 받아 인사말 생성
 *         "greet" to { args ->
 *             val name = args.getOrNull(0)?.toString() ?: "Guest"
 *             "Hello, $name!"
 *         }
 *     )
 * }
 * ```
 */
interface VariableResolverRegistry {
    val resolvers: Map<String, (List<Any?>) -> Any>
}

/**
 * VariableProcessor
 *
 * 여러 개의 [VariableResolverRegistry] 빈을 주입받아,
 * 템플릿 문자열 안의 `%{token}%` 토큰을 찾아 대응하는 resolver 함수를 실행하고,
 * 그 결과값으로 치환해 주는 범용 컴포넌트입니다.
 *
 * - 레지스트리가 하나도 없으면 빈 리스트로 동작(토큰 미등록 시 에러)
 * - 파라미터가 없는 토큰은 process() 호출 시 생략해도 OK
 * - 파라미터가 필요한 토큰은 vararg 로 ("token" to value) 형태로 전달
 *
 * 예:
 * ```
 * val tpl1 = "총 노드 수: %{node_count}%개"
 * val result1 = processor.process(tpl1)
 *
 * val tpl2 = "%{greet}%! 시스템에 오신 걸 환영합니다."
 * val result2 = processor.process(tpl2, "greet" to "Alice")
 * ```
 */
@Component
class VariableProcessor(registries: List<VariableResolverRegistry>) {
    private val resolverMap: Map<String,(List<Any?>)->Any> =
        registries.flatMap { it.resolvers.entries }
            .associate { it.key to it.value }

    private val pattern = Regex("%\\{([^}]+)}%")

    fun process(template: String, vararg params: Pair<String, Any?>) = pattern.replace(template) { m ->
        val token = m.groupValues[1]
        val function = resolverMap[token] ?: throw IllegalArgumentException("지원하지 않는 변수명: $token")
        val argument = params.toMap()[token]
        val args = when (argument) {
            null -> emptyList()
            is Collection<*> -> argument.toList()
            else -> listOf(argument)
        }
        function(args).toString()
    }
}
