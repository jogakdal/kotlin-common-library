package com.hunet.common.lib

import java.util.function.Function

/**
 * Java 친화적 헬퍼.
 * Java 예:
 *  VariableProcessorJava.OptionsBuilder builder = new VariableProcessorJava.OptionsBuilder()
 *      .ignoreCase(true).enableDefaultValue(true).ignoreMissing(true);
 *  VariableProcessor.Options opts = builder.build();
 *  String out = VariableProcessorJava.process(vp, "User=%{name|guest}%", Map.of(), opts);
 */
object VariableProcessorJava {
    class OptionsBuilder {
        private var delimiters: VariableProcessor.Delimiters = VariableProcessor.Delimiters()
        private var ignoreCase: Boolean = true
        private var ignoreMissing: Boolean = false
        private var enableDefaultValue: Boolean = false
        private var defaultDelimiter: Char = '|'
        private var escapeChar: Char = '\\'
        fun delimiters(open: String, close: String) = apply { delimiters = VariableProcessor.Delimiters(open, close) }
        fun ignoreCase(v: Boolean) = apply { ignoreCase = v }
        fun ignoreMissing(v: Boolean) = apply { ignoreMissing = v }
        fun enableDefaultValue(v: Boolean) = apply { enableDefaultValue = v }
        fun defaultDelimiter(c: Char) = apply { defaultDelimiter = c }
        fun escapeChar(c: Char) = apply { escapeChar = c }
        fun build(): VariableProcessor.Options = VariableProcessor.Options(
            delimiters, ignoreCase, ignoreMissing, enableDefaultValue, defaultDelimiter, escapeChar
        )
    }

    @JvmStatic
    @JvmOverloads
    fun process(
        processor: VariableProcessor,
        template: String,
        params: Map<String, Any?> = emptyMap(),
        options: VariableProcessor.Options? = null
    ): String {
        val pairs = params.entries.map { it.key to it.value }.toTypedArray()
        return if (options != null) processor.process(template, options, *pairs) else processor.process(template, *pairs)
    }

    @JvmStatic
    fun process(
        processor: VariableProcessor,
        template: String,
        delimiters: VariableProcessor.Delimiters,
        params: Map<String, Any?>
    ): String {
        val pairs = params.entries.map { it.key to it.value }.toTypedArray()
        return processor.process(template, delimiters, *pairs)
    }

    @JvmStatic
    fun registry(functions: Map<String, Function<List<*>, Any?>>): VariableResolverRegistry = object : VariableResolverRegistry {
        @Suppress("UNCHECKED_CAST")
        override val resolvers: Map<String, (List<Any?>) -> Any> = functions.mapValues { (_, f) ->
            { args: List<Any?> -> f.apply(args) as Any }
        }
    }
}
