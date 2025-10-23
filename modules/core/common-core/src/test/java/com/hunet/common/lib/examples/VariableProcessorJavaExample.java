package com.hunet.common.lib.examples;

import com.hunet.common.lib.VariableProcessor;
import com.hunet.common.lib.VariableProcessorJava;
import com.hunet.common.lib.VariableResolverRegistry;

import java.util.List;
import java.util.Map;

// snippet:vp-java-quickstart:start
/** Java Quick Start 예제 */
public class VariableProcessorJavaExample {

    public static void main(String[] args) {
        VariableResolverRegistry registry = VariableProcessorJava.registry(
                Map.of(
                        "appName",a -> "MyService",
                        "upper", a -> {
                            Object v = a.isEmpty() ? null : a.get(0);
                            return v == null ? "" : v.toString().toUpperCase();
                        },
                        "sum", a -> a.stream()
                                .filter(Number.class::isInstance)
                                .mapToLong(o -> ((Number) o).longValue()).sum(),
                        "greet", a -> "Hello, " + (a.isEmpty() ? "Anonymous" : a.getFirst())
                )
        );
        VariableProcessor vp = new VariableProcessor(List.of(registry));

        String out = VariableProcessorJava.process(
                vp,
                "Service=%{appName}%, USER=%{upper}%",
                Map.of("upper", "Hwang Yongho")
        );
        System.out.println(out); // Service=MyService, USER=HWANG YONGHO

        var opts = new VariableProcessorJava.OptionsBuilder()
                .ignoreCase(true)
                .enableDefaultValue(true)
                .ignoreMissing(true)
                .build();

        String out2 = VariableProcessorJava.process(
                vp,
                "User=%{name|guest}% / Sum=%{sum|0}% / Raw=%{unknown|N/A}%",
                Map.of("sum", List.of(10,20,30), "NAME", "Hwang Yongho"),
                opts
        );
        System.out.println(out2); // User=Hwang Yongho / Sum=60 / Raw=N/A

        String out3 = vp.process(
                "Hi=<<greet>>",
                new VariableProcessor.Delimiters("<<", ">>"),
                Map.of("greet", "Hwang Yongho")
        );
        System.out.println(out3); // Hi=Hello, Hwang Yongho
    }
}
// snippet:vp-java-quickstart:end
