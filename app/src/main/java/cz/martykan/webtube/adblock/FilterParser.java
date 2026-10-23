package cz.martykan.webtube.adblock;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

// Reads filter lists in Adblock Plus / uBlock Origin syntax
public final class FilterParser {
    // uBlock Origin's "!#if" tokens that are true for this app (a Chromium WebView on a phone)
    private static final Set<String> TRUE_CONDITIONS = new HashSet<>(Arrays.asList(
            "env_chromium", "env_mobile", "cap_user_stylesheet", "ext_ublock"));

    // uBlock Origin procedural operators, which CSS cannot express
    private static final String[] PROCEDURAL = {
            ":has-text(", ":-abp-", ":upward(", ":xpath(", ":matches-css", ":min-text-length(", ":watch-attr(",
            ":remove(", ":remove-attr(", ":remove-class(", ":style(", ":others(", ":matches-attr(",
            ":matches-path(", ":matches-prop(", ":matches-media(", ":if(", ":if-not(", ":nth-ancestor(", ":spath("};

    private static final String[] NONE = new String[0];

    public static final class Result {
        final List<NetworkRule> networkRules = new ArrayList<>();
        final List<CosmeticRule> cosmeticRules = new ArrayList<>();
        int unsupported;

        public int ruleCount() {
            return networkRules.size() + cosmeticRules.size();
        }
    }

    private FilterParser() {
    }

    // trusted: the list may use trusted-* scriptlets (only uBlock Origin's own lists)
    public static Result parse(Reader reader, boolean trusted) throws IOException {
        Result result = new Result();
        BufferedReader lines = new BufferedReader(reader);
        Deque<Boolean> conditions = new ArrayDeque<>();
        String line;
        while ((line = lines.readLine()) != null) {
            line = line.trim();
            if (line.startsWith("!#if ")) {
                conditions.push(evaluate(line.substring(5).trim()));
                continue;
            } else if (line.startsWith("!#else")) {
                if (!conditions.isEmpty()) {
                    conditions.push(!conditions.pop());
                }
                continue;
            } else if (line.startsWith("!#endif")) {
                if (!conditions.isEmpty()) {
                    conditions.pop();
                }
                continue;
            }
            if (conditions.contains(false) || line.isEmpty() || line.startsWith("!") || line.startsWith("[")) {
                continue;
            }
            if (!parseLine(line, trusted, result)) {
                result.unsupported++;
            }
        }
        return result;
    }

    private static boolean parseLine(String line, boolean trusted, Result result) {
        int separator = line.indexOf('#');
        while (separator >= 0) {
            String rest = line.substring(separator);
            if (rest.startsWith("##") || rest.startsWith("#@#") || rest.startsWith("#?#") || rest.startsWith("#@?#")
                    || rest.startsWith("#$#") || rest.startsWith("#@$#") || rest.startsWith("#%#") || rest.startsWith("#@%#")) {
                return parseCosmetic(line.substring(0, separator), rest, trusted, result);
            }
            separator = line.indexOf('#', separator + 1);
        }
        NetworkRule rule = NetworkRule.parse(line);
        if (rule == null) {
            return false;
        }
        result.networkRules.add(rule);
        return true;
    }

    private static boolean parseCosmetic(String domains, String rest, boolean trusted, Result result) {
        boolean exception = rest.startsWith("#@#");
        if (!rest.startsWith("##") && !exception) {
            return false; // procedural (#?#), CSS injection (#$#) and AdGuard scriptlets (#%#)
        }
        String body = rest.substring(exception ? 3 : 2).trim();
        if (body.isEmpty() || body.startsWith("^")) {
            return false; // HTML filters
        }

        List<String> include = new ArrayList<>();
        List<String> exclude = new ArrayList<>();
        if (!domains.isEmpty()) {
            for (String domain : domains.split(",")) {
                domain = domain.trim().toLowerCase(Locale.ROOT);
                if (domain.isEmpty() || domain.startsWith("/") || domain.contains(">>")) {
                    return false;
                }
                if (domain.startsWith("~")) {
                    exclude.add(domain.substring(1));
                } else {
                    include.add(domain);
                }
            }
        }
        String[] includeDomains = include.toArray(NONE);
        String[] excludeDomains = exclude.toArray(NONE);

        if (body.startsWith("+js(") && body.endsWith(")")) {
            List<String> args = CosmeticRule.parseScriptletArgs(body.substring(4, body.length() - 1));
            String name = args.get(0);
            if (name.endsWith(".js")) {
                args.set(0, name.substring(0, name.length() - 3));
            }
            if (includeDomains.length == 0 && !(exception && name.isEmpty())) {
                return false; // generic scriptlets are not supported by uBlock Origin either
            }
            result.cosmeticRules.add(new CosmeticRule(exception, null, args, includeDomains, excludeDomains, trusted));
            return true;
        }
        for (String operator : PROCEDURAL) {
            if (body.contains(operator)) {
                return false;
            }
        }
        if (body.contains("{") || body.contains("}")) {
            return false;
        }
        result.cosmeticRules.add(new CosmeticRule(exception, body, null, includeDomains, excludeDomains, trusted));
        return true;
    }

    // "env_mobile", "!env_firefox", "env_chromium && !cap_html_filtering", "(a || b)"
    static boolean evaluate(String expression) {
        return new ConditionParser(expression.replace(" ", "")).parseOr();
    }

    private static final class ConditionParser {
        private final String text;
        private int position;

        ConditionParser(String text) {
            this.text = text;
        }

        boolean parseOr() {
            boolean value = parseAnd();
            while (text.startsWith("||", position)) {
                position += 2;
                value |= parseAnd();
            }
            return value;
        }

        boolean parseAnd() {
            boolean value = parseUnary();
            while (text.startsWith("&&", position)) {
                position += 2;
                value &= parseUnary();
            }
            return value;
        }

        boolean parseUnary() {
            if (position < text.length() && text.charAt(position) == '!') {
                position++;
                return !parseUnary();
            }
            if (position < text.length() && text.charAt(position) == '(') {
                position++;
                boolean value = parseOr();
                if (position < text.length() && text.charAt(position) == ')') {
                    position++;
                }
                return value;
            }
            int start = position;
            while (position < text.length() && (Character.isLetterOrDigit(text.charAt(position)) || text.charAt(position) == '_')) {
                position++;
            }
            return TRUE_CONDITIONS.contains(text.substring(start, position));
        }
    }
}
