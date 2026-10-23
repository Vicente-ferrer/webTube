package cz.martykan.webtube.adblock;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

// Host name helpers for filter rules, without a full public suffix list
final class Domains {
    // Second-level labels used under country code TLDs, as in co.uk or com.br
    private static final Set<String> SECOND_LEVEL = new HashSet<>(Arrays.asList(
            "ac", "co", "com", "edu", "gob", "gov", "go", "mil", "ne", "net", "nic", "or", "org"));

    private Domains() {
    }

    static String hostOf(String url) {
        int start = url.indexOf("://");
        if (start < 0) {
            return "";
        }
        start += 3;
        int end = start;
        while (end < url.length() && "/?#".indexOf(url.charAt(end)) < 0) {
            end++;
        }
        String host = url.substring(start, end);
        int at = host.lastIndexOf('@');
        if (at >= 0) {
            host = host.substring(at + 1);
        }
        int colon = host.lastIndexOf(':');
        if (colon >= 0 && host.indexOf(']') < colon) {
            host = host.substring(0, colon);
        }
        return host.toLowerCase(Locale.ROOT);
    }

    // example.com for www.example.com, example.co.uk for a.example.co.uk
    static String registrable(String host) {
        if (host.isEmpty() || Character.isDigit(host.charAt(host.length() - 1)) || host.indexOf(':') >= 0) {
            return host; // IP address
        }
        String[] labels = host.split("\\.");
        int n = labels.length;
        if (n <= 2) {
            return host;
        }
        boolean twoLevelSuffix = labels[n - 1].length() == 2 && SECOND_LEVEL.contains(labels[n - 2]);
        int keep = twoLevelSuffix ? 3 : 2;
        StringBuilder result = new StringBuilder();
        for (int i = n - keep; i < n; i++) {
            if (result.length() > 0) {
                result.append('.');
            }
            result.append(labels[i]);
        }
        return result.toString();
    }

    static boolean isThirdParty(String requestHost, String pageHost) {
        return !registrable(requestHost).equals(registrable(pageHost));
    }

    // "example.com" matches example.com and its subdomains; "example.*" also matches any TLD
    static boolean matches(String host, String domain) {
        if (domain.endsWith(".*")) {
            String name = domain.substring(0, domain.length() - 1);
            int from = 0;
            while (true) {
                int index = host.indexOf(name, from);
                if (index < 0) {
                    return false;
                }
                if (index == 0 || host.charAt(index - 1) == '.') {
                    String suffix = host.substring(index + name.length());
                    if (!suffix.isEmpty() && registrable("x." + suffix).equals("x." + suffix)) {
                        return true;
                    }
                }
                from = index + 1;
            }
        }
        return host.equals(domain) || (host.endsWith(domain) && host.charAt(host.length() - domain.length() - 1) == '.');
    }

    // Domain lists of rules: "a.com,~b.a.com" (cosmetic) or "a.com|~b.a.com" (network)
    static boolean listMatches(String host, String[] include, String[] exclude) {
        for (String domain : exclude) {
            if (matches(host, domain)) {
                return false;
            }
        }
        if (include.length == 0) {
            return true;
        }
        for (String domain : include) {
            if (matches(host, domain)) {
                return true;
            }
        }
        return false;
    }
}
