package cz.martykan.webtube.adblock;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

// A request blocking (or exception, "@@") rule in Adblock Plus / uBlock Origin syntax
final class NetworkRule {
    static final int TYPE_DOCUMENT = 1;
    static final int TYPE_SCRIPT = 1 << 1;
    static final int TYPE_IMAGE = 1 << 2;
    static final int TYPE_STYLESHEET = 1 << 3;
    static final int TYPE_XHR = 1 << 4;
    static final int TYPE_MEDIA = 1 << 5;
    static final int TYPE_FONT = 1 << 6;
    static final int TYPE_SUBDOCUMENT = 1 << 7;
    static final int TYPE_WEBSOCKET = 1 << 8;
    static final int TYPE_PING = 1 << 9;
    static final int TYPE_OTHER = 1 << 10;
    // Rules without a type option never apply to the page itself
    static final int TYPES_DEFAULT = (1 << 11) - 1 & ~TYPE_DOCUMENT;

    private static final int PARTY_ANY = 0;
    private static final int PARTY_FIRST = 1;
    private static final int PARTY_THIRD = 2;
    private static final String[] NONE = new String[0];

    final boolean exception;
    final boolean important;
    final String pattern;
    private final boolean matchCase;
    private final int types;
    private final int party;
    private final String[] includeDomains;
    private final String[] excludeDomains;
    private Pattern regex;

    private NetworkRule(boolean exception, boolean important, String pattern, boolean matchCase, int types, int party,
                        String[] includeDomains, String[] excludeDomains) {
        this.exception = exception;
        this.important = important;
        this.pattern = pattern;
        this.matchCase = matchCase;
        this.types = types;
        this.party = party;
        this.includeDomains = includeDomains;
        this.excludeDomains = excludeDomains;
    }

    // Null when the line uses syntax or options this engine does not support
    static NetworkRule parse(String line) {
        boolean exception = line.startsWith("@@");
        String rule = exception ? line.substring(2) : line;

        String options = null;
        int dollar = rule.lastIndexOf('$');
        if (dollar >= 0 && !(rule.startsWith("/") && rule.indexOf('/', 1) > dollar)) {
            options = rule.substring(dollar + 1);
            rule = rule.substring(0, dollar);
        }
        if (rule.length() > 1 && rule.startsWith("/") && rule.endsWith("/")) {
            return null; // regular expression rules
        }
        if (isHostname(rule)) {
            rule = "||" + rule + "^"; // uBlock Origin: a bare host name blocks that host
        }

        boolean important = false;
        boolean matchCase = false;
        int types = 0;
        int excludedTypes = 0;
        int party = PARTY_ANY;
        String[] include = NONE;
        String[] exclude = NONE;
        if (options != null) {
            for (String option : options.split(",")) {
                boolean negated = option.startsWith("~");
                String name = negated ? option.substring(1) : option;
                int type = typeOf(name);
                if (type != 0) {
                    if (negated) {
                        excludedTypes |= type;
                    } else {
                        types |= type;
                    }
                    continue;
                }
                switch (name) {
                    case "third-party":
                    case "3p":
                    case "strict3p":
                        party = negated ? PARTY_FIRST : PARTY_THIRD;
                        break;
                    case "first-party":
                    case "1p":
                    case "strict1p":
                        party = negated ? PARTY_THIRD : PARTY_FIRST;
                        break;
                    case "important":
                        important = true;
                        break;
                    case "match-case":
                        matchCase = true;
                        break;
                    case "all":
                        types |= TYPES_DEFAULT | TYPE_DOCUMENT;
                        break;
                    case "redirect":
                    case "_":
                        break; // blocked with an empty response instead of a replacement resource
                    default:
                        if (name.startsWith("domain=") || name.startsWith("from=")) {
                            List<String> in = new ArrayList<>();
                            List<String> out = new ArrayList<>();
                            for (String domain : name.substring(name.indexOf('=') + 1).split("\\|")) {
                                if (domain.startsWith("/")) {
                                    return null;
                                }
                                (domain.startsWith("~") ? out : in).add(domain.replace("~", "").toLowerCase(Locale.ROOT));
                            }
                            include = in.toArray(NONE);
                            exclude = out.toArray(NONE);
                        } else if (name.startsWith("redirect=")) {
                            break;
                        } else {
                            return null; // removeparam, csp, popup, badfilter, ...
                        }
                }
            }
        }
        if (types == 0) {
            types = TYPES_DEFAULT;
        }
        types &= ~excludedTypes;

        String pattern = matchCase ? rule : rule.toLowerCase(Locale.ROOT);
        return new NetworkRule(exception, important, pattern, matchCase, types, party, include, exclude);
    }

    private static int typeOf(String option) {
        switch (option) {
            case "document":
            case "doc":
                return TYPE_DOCUMENT;
            case "script":
                return TYPE_SCRIPT;
            case "image":
                return TYPE_IMAGE;
            case "stylesheet":
            case "css":
                return TYPE_STYLESHEET;
            case "xmlhttprequest":
            case "xhr":
                return TYPE_XHR;
            case "media":
                return TYPE_MEDIA;
            case "font":
                return TYPE_FONT;
            case "subdocument":
            case "frame":
                return TYPE_SUBDOCUMENT;
            case "websocket":
                return TYPE_WEBSOCKET;
            case "ping":
            case "beacon":
                return TYPE_PING;
            case "other":
            case "object":
                return TYPE_OTHER;
            default:
                return 0;
        }
    }

    private static boolean isHostname(String rule) {
        if (rule.indexOf('.') <= 0 || rule.endsWith(".")) {
            return false;
        }
        for (int i = 0; i < rule.length(); i++) {
            char c = rule.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '-';
            if (!allowed) {
                return false;
            }
        }
        return rule.charAt(0) != '-';
    }

    boolean matches(String url, String lowerUrl, String requestHost, String pageHost, int type) {
        if ((types & type) == 0) {
            return false;
        }
        if (party != PARTY_ANY && (party == PARTY_THIRD) != Domains.isThirdParty(requestHost, pageHost)) {
            return false;
        }
        if ((includeDomains.length > 0 || excludeDomains.length > 0)
                && !Domains.listMatches(pageHost, includeDomains, excludeDomains)) {
            return false;
        }
        return regex().matcher(matchCase ? url : lowerUrl).find();
    }

    private Pattern regex() {
        if (regex == null) {
            regex = Pattern.compile(toRegex(pattern));
        }
        return regex;
    }

    static String toRegex(String pattern) {
        StringBuilder re = new StringBuilder();
        int i = 0;
        int end = pattern.length();
        if (pattern.startsWith("||")) {
            re.append("^[a-zA-Z][a-zA-Z0-9.+-]*://(?:[^/?#]*\\.)?");
            i = 2;
        } else if (pattern.startsWith("|")) {
            re.append('^');
            i = 1;
        }
        boolean anchoredEnd = end > i && pattern.charAt(end - 1) == '|';
        if (anchoredEnd) {
            end--;
        }
        for (; i < end; i++) {
            char c = pattern.charAt(i);
            if (c == '*') {
                re.append(".*");
            } else if (c == '^') {
                re.append("(?:[^a-zA-Z0-9_.%-]|$)");
            } else if (Character.isLetterOrDigit(c)) {
                re.append(c);
            } else {
                re.append('\\').append(c);
            }
        }
        if (anchoredEnd) {
            re.append('$');
        }
        return re.toString();
    }

    // Lowercase runs of [a-z0-9%] that any matching URL must contain as a whole token
    List<String> tokens() {
        List<String> tokens = new ArrayList<>();
        String p = pattern.toLowerCase(Locale.ROOT);
        int begin = p.startsWith("||") ? 2 : p.startsWith("|") ? 1 : 0;
        int end = p.length() > begin && p.endsWith("|") ? p.length() - 1 : p.length();
        int i = begin;
        while (i < end) {
            if (!isTokenChar(p.charAt(i))) {
                i++;
                continue;
            }
            int start = i;
            while (i < end && isTokenChar(p.charAt(i))) {
                i++;
            }
            // A token next to a wildcard or an unanchored end can be part of a longer URL token
            boolean leftBounded = start == begin ? begin > 0 : p.charAt(start - 1) != '*';
            boolean rightBounded = i == end ? end < p.length() : p.charAt(i) != '*';
            if (leftBounded && rightBounded && i - start >= 2) {
                tokens.add(p.substring(start, i));
            }
        }
        return tokens;
    }

    static boolean isTokenChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '%';
    }
}
