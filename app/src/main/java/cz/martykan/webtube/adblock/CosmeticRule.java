package cz.martykan.webtube.adblock;

import java.util.ArrayList;
import java.util.List;

// "domains##selector" (element hiding) or "domains##+js(name, args)" (scriptlet), or their "#@#" exceptions
final class CosmeticRule {
    final boolean exception;
    final boolean scriptlet;
    // The CSS selector, or the scriptlet name followed by its arguments
    final String selector;
    final List<String> scriptletArgs;
    final String[] includeDomains;
    final String[] excludeDomains;
    // From a list allowed to use trusted-* scriptlets
    final boolean trusted;

    CosmeticRule(boolean exception, String selector, List<String> scriptletArgs, String[] includeDomains,
                 String[] excludeDomains, boolean trusted) {
        this.exception = exception;
        this.scriptlet = scriptletArgs != null;
        this.selector = selector;
        this.scriptletArgs = scriptletArgs;
        this.includeDomains = includeDomains;
        this.excludeDomains = excludeDomains;
        this.trusted = trusted;
    }

    boolean isGeneric() {
        return includeDomains.length == 0;
    }

    boolean appliesTo(String host) {
        return Domains.listMatches(host, includeDomains, excludeDomains);
    }

    // The same scriptlet call, ignoring whitespace differences, as used to match exceptions
    String scriptletKey() {
        return String.join(",", scriptletArgs);
    }

    // "name, arg1, 'quoted, arg', arg\, with comma" -> [name, arg1, quoted, arg, arg, with comma]
    static List<String> parseScriptletArgs(String body) {
        List<String> args = new ArrayList<>();
        int i = 0;
        int n = body.length();
        while (i <= n) {
            while (i < n && body.charAt(i) == ' ') {
                i++;
            }
            StringBuilder arg = new StringBuilder();
            char quote = i < n ? body.charAt(i) : 0;
            if (quote == '\'' || quote == '"' || quote == '`') {
                int close = body.indexOf(quote, i + 1);
                while (close > 0 && body.charAt(close - 1) == '\\') {
                    close = body.indexOf(quote, close + 1);
                }
                int comma = close > 0 ? body.indexOf(',', close) : -1;
                String rest = close > 0 ? body.substring(close + 1, comma < 0 ? n : comma).trim() : null;
                if (close > 0 && rest.isEmpty()) {
                    args.add(body.substring(i + 1, close));
                    i = comma < 0 ? n + 1 : comma + 1;
                    continue;
                }
            }
            while (i < n) {
                char c = body.charAt(i);
                if (c == '\\' && i + 1 < n && body.charAt(i + 1) == ',') {
                    arg.append(',');
                    i += 2;
                } else if (c == ',') {
                    break;
                } else {
                    arg.append(c);
                    i++;
                }
            }
            args.add(arg.toString().trim());
            i++;
        }
        return args;
    }
}
