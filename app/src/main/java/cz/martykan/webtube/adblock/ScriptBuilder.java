package cz.martykan.webtube.adblock;

import java.util.List;

// Builds the script injected at document start: the scriptlets and element hiding for one host
public final class ScriptBuilder {
    private ScriptBuilder() {
    }

    // library: assets/scriptlets.js, an expression returning {run(name, args), hide(selectors)}.
    // Null when nothing applies to the host.
    public static String build(String library, FilterEngine engine, String host) {
        List<List<String>> calls = engine.scriptletsFor(host);
        List<String> selectors = engine.selectorsFor(host);
        if (calls.isEmpty() && selectors.isEmpty()) {
            return null;
        }
        StringBuilder js = new StringBuilder("(function () {\n'use strict';\nvar scriptlets = ")
                .append(library.trim().replaceAll(";$", ""))
                .append(";\n");
        for (List<String> call : calls) {
            js.append("scriptlets.run(").append(quote(call.get(0))).append(", ")
                    .append(array(call.subList(1, call.size()))).append(");\n");
        }
        if (!selectors.isEmpty()) {
            js.append("scriptlets.hide(").append(array(selectors)).append(");\n");
        }
        return js.append("})();\n").toString();
    }

    static String array(List<String> values) {
        StringBuilder js = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                js.append(", ");
            }
            js.append(quote(values.get(i)));
        }
        return js.append(']').toString();
    }

    // A JavaScript string literal; list contents must never escape it
    static String quote(String value) {
        StringBuilder js = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    js.append("\\\"");
                    break;
                case '\\':
                    js.append("\\\\");
                    break;
                case '\n':
                    js.append("\\n");
                    break;
                case '\r':
                    js.append("\\r");
                    break;
                case '\t':
                    js.append("\\t");
                    break;
                default:
                    if (c < 0x20 || c == '<' || c == '>' || c == ' ' || c == ' ' || c >= 0x7f) {
                        js.append(String.format("\\u%04x", (int) c));
                    } else {
                        js.append(c);
                    }
            }
        }
        return js.append('"').toString();
    }
}
