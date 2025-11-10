package com.hunet.common.util

import java.text.SimpleDateFormat
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.*

typealias Timestamp = Long

// lambda 형식에서 overload된 function을 구별하는 방법이 제공되지 않아 정의함
private fun _ofEpochSecond(p: Long) = Instant.ofEpochSecond(p)
private fun _ofEpochMicro(p: Long) = Instant.ofEpochMilli(p / 1000)
private fun _ofEpochNano(p: Long) = _ofEpochMicro(p / 1000)

enum class ChronoType(val chronoUnit: (Long) -> Instant) {
    SECOND(::_ofEpochSecond),
    MILLI(Instant::ofEpochMilli),
    MICRO(::_ofEpochMicro),
    NANO(::_ofEpochNano)
}

const val DF_GENERAL_DATE_PATTERN = "yyyy-MM-dd"
const val DF_SHORT_DATE_PATTERN = "yyyyMMdd"
const val DF_GENERAL_DATETIME_PATTERN = "yyyy-MM-dd HH:mm:ss"
const val DF_SLASH_DATE_PATTERN = "yyyy/MM/dd"
const val DF_KOREAN_DATE_PATTERN = "yyyy년 MM월 dd일"
const val DF_YEAR_MONTH_PATTERN = "yyyy-MM"

/**
 * 해당일 시작의 타임스탬프
 */
fun LocalDate.toEpochSecondAtStartOfDay() = atStartOfDay(ZoneId.systemDefault()).toEpochSecond()

/**
 * 특정일시의 타임스탬프
 */
fun LocalDateTime.toEpochSecond() = atZone(ZoneId.systemDefault()).toEpochSecond()

/**
 * Date를 TimeStamp로 변환
 */
fun Date.toTimeStamp()
        = toLocalDate().atStartOfDay(ZoneId.systemDefault()).toInstant().epochSecond

/**
 * LocalDateTime 을 Date 로 변환
 */
fun LocalDateTime.toDate(): Date = Date.from(atZone(ZoneId.systemDefault()).toInstant())

/**
 * LocalDate 을 Date 로 변환
 */
fun LocalDate.toDate(): Date = Date.from(atStartOfDay(ZoneId.systemDefault()).toInstant())

/**
 * Date 를 LocalDate 로 변환
 */
fun Date.toLocalDate() = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).toLocalDate()

/**
 * Date 를 LocalDateTime 으로 변환
 */
fun Date.toLocalDateTime()
        = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).toLocalDateTime()

/**
 * Date 를 epoch 세컨드로 변환
 */
fun Date.toEpochSecond() = time / 1000

/**
 * String을 Date로 변환
 */
fun String.toDate() = when {
    matches(Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$")) ->
        LocalDate.parse(this, DateTimeFormatter.ofPattern(DF_GENERAL_DATE_PATTERN)).toDate()

    matches(Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}$")) ->
        LocalDateTime.parse(this, DateTimeFormatter.ofPattern(DF_GENERAL_DATETIME_PATTERN))
            .toDate()

    else -> throw IllegalArgumentException(this)
}

fun stringToDate(dateStr: String) = dateStr.toDate()

/**
 * Date를 String으로 변환
 */
fun Date.toDateString(formatString: String = DF_GENERAL_DATE_PATTERN)
        = SimpleDateFormat(formatString).format(this)
fun Date.toString(formatString: String = DF_GENERAL_DATE_PATTERN) = toDateString(formatString)
fun Date.toDBString(formatString: String = DF_GENERAL_DATETIME_PATTERN)
        = toDateString(formatString)

/**
 * Date를 짧은 날짜 타입으로 변환
 */
val Date.shortFormat get() = toDateString(DF_SHORT_DATE_PATTERN)
val Date.slashFormat get() = toDateString(DF_SLASH_DATE_PATTERN)

/**
 * Date를 한국식 년월일 패턴으로 변환
 */
val Date.koreanFormat: String get() = toDateString(DF_KOREAN_DATE_PATTERN)

/**
 * LocalDateTime를 yyyy-MM-dd HH:mm:ss 형태로 변환
 */
fun LocalDateTime.toDBString(formatString: String = DF_GENERAL_DATETIME_PATTERN)
        = format(DateTimeFormatter.ofPattern(formatString))

/**
 * 타임스탬프 일단위 +, -
 */
fun Timestamp.addDay(days: Int) = this + (days * 86400)

/**
 * 타임스탬프 to LocalDate 변환
 */
fun Timestamp.toLocalDate(chronoType: ChronoType = ChronoType.SECOND)
        = chronoType.chronoUnit(this).atZone(ZoneId.systemDefault()).toLocalDate()

/**
 * 타임스탬프 to LocalDateTime 변환
 */
fun Timestamp.toLocalDateTime(chronoType: ChronoType = ChronoType.SECOND)
        = LocalDateTime.ofInstant(chronoType.chronoUnit(this), TimeZone.getDefault().toZoneId())

/**
 * 타임스탬프 to LocalTime 변환
 */
fun Timestamp.toLocalTime(chronoType: ChronoType = ChronoType.SECOND)
        = LocalTime.ofInstant(chronoType.chronoUnit(this), TimeZone.getDefault().toZoneId())

/**
 * 타임스탬프 to LocalTime 변환
 */
fun Timestamp.toAbsTime(chronoType: ChronoType = ChronoType.SECOND)
        = LocalTime.ofInstant(chronoType.chronoUnit(this), TimeZone.getDefault().toZoneId())

fun Timestamp.toDate(chronoType: ChronoType = ChronoType.SECOND)
        = toLocalDateTime(chronoType).toDate()

/**
 * 시각 문자열을 LocalTime 형식으로 변환
 * 변환에 실패한 경우 00시를 리턴
 */
val String.toHour: LocalTime
    get() =
        if (isDigit) {
            toInt().let { hour ->
                if (hour <= 0) LocalTime.MIDNIGHT
                else LocalTime.of(hour % 24, 0, 0)
            }
        }
        else LocalTime.MIDNIGHT

/**
 * Date 날짜 변경
 */
fun Date.plusDays(n: Long): Date = toLocalDate().plusDays(n).toDate()
fun Date.plusWeeks(n: Long): Date = toLocalDate().plusWeeks(n).toDate()
fun Date.plusMonths(n: Long): Date = toLocalDate().plusMonths(n).toDate()
fun Date.plusYears(n: Long): Date = toLocalDate().plusYears(n).toDate()

fun Date.minusDays(n: Long): Date = toLocalDate().plusDays(-n).toDate()
fun Date.minusWeeks(n: Long): Date = toLocalDate().plusWeeks(-n).toDate()
fun Date.minusMonths(n: Long): Date = toLocalDate().plusMonths(-n).toDate()
fun Date.minusYears(n: Long): Date = toLocalDate().plusYears(-n).toDate()

/**
 * Date 타입 값 + 특정 시간
 *  hours: 더할 시간 값
 *  startHour:  기준 시각. 생략되면 this의 시각 값
 */
fun Date.plusHours(hours: Long, startHour: Int = toLocalDateTime().hour) =
    toLocalDate().atTime(LocalTime.of(startHour % 24, 0, 0)).plusHours(hours).toDate()

fun Date.plusHours(hours: Int, startHour: Int = toLocalDateTime().hour) =
    plusHours(hours.toLong(), startHour)

val Date.toDateOnly get() = toLocalDate().toDate()

val Date.dayOfWeek get() = toLocalDate().dayOfWeek
val Date.dayOfMonth get() = toLocalDate().dayOfMonth

val LocalDate.firstDateOfMonth: LocalDate get() = LocalDate.of(year, month, 1)
val LocalDate.lastDateOfMonth: LocalDate get() = plusMonths(1).minusDays(1)
