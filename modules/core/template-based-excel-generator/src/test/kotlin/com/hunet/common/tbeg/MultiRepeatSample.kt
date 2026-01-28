package com.hunet.common.tbeg

import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

/**
 * 다중 repeat 영역 샘플.
 *
 * multi_repeat_template.xlsx 템플릿에는 두 개의 독립적인 repeat 영역이 있습니다:
 * 1. employees (A6:C6) - 직원 목록
 * 2. departure (F12:H12) - 부서 목록
 */
object MultiRepeatSample {

    data class Employee(val name: String, val position: String, val salary: Int)
    data class Department(val name: String, val members: Int, val office: String)

    @JvmStatic
    fun main(args: Array<String>) {
        val moduleDir = findModuleDir()
        val outputDir = moduleDir.resolve("build/samples")
        Files.createDirectories(outputDir)

        println("=" .repeat(60))
        println("다중 Repeat 영역 샘플")
        println("=" .repeat(60))

        // 직원 데이터 (15명)
        val employees = listOf(
            Employee("황용호", "부장", 8000),
            Employee("한용호", "과장", 6500),
            Employee("홍용호", "대리", 4500),
            Employee("김용호", "사원", 3500),
            Employee("이용호", "사원", 3200),
            Employee("박용호", "차장", 7200),
            Employee("최용호", "과장", 6200),
            Employee("정용호", "대리", 4800),
            Employee("강용호", "사원", 3300),
            Employee("조용호", "사원", 3100),
            Employee("윤용호", "부장", 8200),
            Employee("장용호", "차장", 7000),
            Employee("임용호", "과장", 6300),
            Employee("오용호", "대리", 4600),
            Employee("신용호", "사원", 3400)
        )

        // 부서 데이터 (7개)
        val departments = listOf(
            Department("개발팀", 15, "본관 3층"),
            Department("기획팀", 8, "본관 2층"),
            Department("인사팀", 5, "별관 1층"),
            Department("영업팀", 12, "본관 1층"),
            Department("마케팅팀", 10, "본관 4층"),
            Department("재무팀", 6, "별관 2층"),
            Department("총무팀", 4, "별관 1층")
        )

        // 데이터 프로바이더 생성
        val dataProvider = simpleDataProvider {
            value("title", "2026년 직원 및 부서 현황")
            value("date", LocalDate.now().toString())
            value("secondTitle", "부서별 현황")
            value("linkText", "(주)휴넷 홈페이지")
            value("url", "https://www.hunet.co.kr")

            // 이미지
            image("logo", loadImage("hunet_logo.png") ?: byteArrayOf())
            image("ci", loadImage("hunet_ci.png") ?: byteArrayOf())

            // 직원 목록 (첫 번째 repeat 영역)
            items("employees") { employees.iterator() }

            // 부서 목록 (두 번째 repeat 영역)
            items("departure") { departments.iterator() }
        }

        println("\n[데이터]")
        println("-" .repeat(40))
        println("직원 수: ${employees.size}명")
        employees.forEach { println("  - ${it.name} (${it.position}, ${it.salary}만원)") }
        println("\n부서 수: ${departments.size}개")
        departments.forEach { println("  - ${it.name} (${it.members}명, ${it.office})") }

        // XSSF 모드로 생성
        println("\n[XSSF 모드 생성]")
        println("-" .repeat(40))

        val xssfConfig = ExcelGeneratorConfig(streamingMode = StreamingMode.DISABLED)
        ExcelGenerator(xssfConfig).use { generator ->
            val template = loadTemplate()
            val resultPath = generator.generateToFile(
                template = template,
                dataProvider = dataProvider,
                outputDir = outputDir,
                baseFileName = "multi_repeat_xssf"
            )
            println("결과: $resultPath")
        }

        // SXSSF 모드로 생성
        println("\n[SXSSF 모드 생성]")
        println("-" .repeat(40))

        ExcelGenerator().use { generator ->
            val template = loadTemplate()
            val resultPath = generator.generateToFile(
                template = template,
                dataProvider = dataProvider,
                outputDir = outputDir,
                baseFileName = "multi_repeat_sxssf"
            )
            println("결과: $resultPath")
        }

        println("\n" + "=" .repeat(60))
        println("샘플 폴더: ${outputDir.toAbsolutePath()}")
        println("=" .repeat(60))
    }

    private fun loadTemplate() =
        MultiRepeatSample::class.java.getResourceAsStream("/templates/multi_repeat_template.xlsx")
            ?: throw IllegalStateException("템플릿 파일을 찾을 수 없습니다: /templates/multi_repeat_template.xlsx")

    private fun loadImage(fileName: String): ByteArray? =
        MultiRepeatSample::class.java.getResourceAsStream("/$fileName")?.use { it.readBytes() }

    private fun findModuleDir(): Path {
        val classLocation = MultiRepeatSample::class.java.protectionDomain.codeSource?.location
        if (classLocation != null) {
            val classPath = Path.of(classLocation.toURI())
            var current = classPath
            while (current.parent != null) {
                if (current.fileName?.toString() == "build" &&
                    Files.exists(current.resolveSibling("src"))
                ) {
                    return current.parent
                }
                current = current.parent
            }
        }

        val workingDir = Path.of("").toAbsolutePath()
        if (Files.exists(workingDir.resolve("src/main/kotlin/com/hunet/common/tbeg"))) {
            return workingDir
        }

        val moduleFromRoot = workingDir.resolve("modules/core/template-based-excel-generator")
        if (Files.exists(moduleFromRoot)) {
            return moduleFromRoot
        }

        return workingDir
    }
}
