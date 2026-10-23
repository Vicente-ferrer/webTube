package cz.martykan.webtube.adblock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

// Answers "block this request?", "which elements to hide?" and "which scriptlets to run?"
// for the rules of all enabled filter lists. Immutable once built, safe to use from any thread.
public final class FilterEngine {
    // Too common in URLs to narrow down the candidate rules
    private static final Set<String> BAD_TOKENS = new HashSet<>(Arrays.asList(
            "http", "https", "www", "com", "net", "org", "js", "html", "php", "png", "jpg", "gif", "css"));

    private final Map<String, List<NetworkRule>> blockRules = new HashMap<>();
    private final Map<String, List<NetworkRule>> exceptionRules = new HashMap<>();
    private final List<NetworkRule> untokenizedBlockRules = new ArrayList<>();
    private final List<NetworkRule> untokenizedExceptionRules = new ArrayList<>();
    private final List<CosmeticRule> cosmeticRules = new ArrayList<>();
    private int networkRuleCount;

    public FilterEngine(List<FilterParser.Result> lists) {
        for (FilterParser.Result list : lists) {
            for (NetworkRule rule : list.networkRules) {
                add(rule);
            }
            for (CosmeticRule rule : list.cosmeticRules) {
                // Generic element hiding would mean thousands of selectors for sites this app never opens
                if (!rule.isGeneric() || rule.exception) {
                    cosmeticRules.add(rule);
                }
            }
        }
    }

    private void add(NetworkRule rule) {
        networkRuleCount++;
        Map<String, List<NetworkRule>> index = rule.exception ? exceptionRules : blockRules;
        String best = null;
        int bestSize = Integer.MAX_VALUE;
        for (String token : rule.tokens()) {
            if (BAD_TOKENS.contains(token)) {
                continue;
            }
            List<NetworkRule> bucket = index.get(token);
            int size = bucket == null ? 0 : bucket.size();
            if (size < bestSize || (size == bestSize && token.length() > best.length())) {
                best = token;
                bestSize = size;
            }
        }
        if (best == null) {
            (rule.exception ? untokenizedExceptionRules : untokenizedBlockRules).add(rule);
        } else {
            List<NetworkRule> bucket = index.get(best);
            if (bucket == null) {
                bucket = new ArrayList<>();
                index.put(best, bucket);
            }
            bucket.add(rule);
        }
    }

    public int networkRuleCount() {
        return networkRuleCount;
    }

    public int cosmeticRuleCount() {
        return cosmeticRules.size();
    }

    // type: one of the NetworkRule.TYPE_* values
    public boolean shouldBlock(String url, String pageHost, int type) {
        return blockingRule(url, pageHost, type) != null;
    }

    // The rule that blocks this request, or null
    NetworkRule blockingRule(String url, String pageHost, int type) {
        String lowerUrl = url.toLowerCase(Locale.ROOT);
        String requestHost = Domains.hostOf(url);
        List<String> tokens = urlTokens(lowerUrl);

        NetworkRule block = find(blockRules, untokenizedBlockRules, tokens, url, lowerUrl, requestHost, pageHost, type, true);
        if (block == null || block.important) {
            return block;
        }
        boolean excepted = find(exceptionRules, untokenizedExceptionRules, tokens, url, lowerUrl, requestHost, pageHost, type, false) != null;
        return excepted ? null : block;
    }

    // An "@@||site^$document" exception turns blocking off on that page
    public boolean isPageAllowlisted(String pageUrl) {
        String lowerUrl = pageUrl.toLowerCase(Locale.ROOT);
        String host = Domains.hostOf(pageUrl);
        return find(exceptionRules, untokenizedExceptionRules, urlTokens(lowerUrl), pageUrl, lowerUrl, host, host,
                NetworkRule.TYPE_DOCUMENT, false) != null;
    }

    private static NetworkRule find(Map<String, List<NetworkRule>> index, List<NetworkRule> untokenized, List<String> tokens,
                                    String url, String lowerUrl, String requestHost, String pageHost, int type, boolean preferImportant) {
        NetworkRule found = null;
        for (String token : tokens) {
            List<NetworkRule> bucket = index.get(token);
            if (bucket != null) {
                for (NetworkRule rule : bucket) {
                    if (rule.matches(url, lowerUrl, requestHost, pageHost, type)) {
                        if (!preferImportant || rule.important) {
                            return rule;
                        }
                        found = rule;
                    }
                }
            }
        }
        for (NetworkRule rule : untokenized) {
            if (rule.matches(url, lowerUrl, requestHost, pageHost, type)) {
                if (!preferImportant || rule.important) {
                    return rule;
                }
                if (found == null) {
                    found = rule;
                }
            }
        }
        return found;
    }

    private static List<String> urlTokens(String lowerUrl) {
        Set<String> tokens = new LinkedHashSet<>();
        int i = 0;
        int n = lowerUrl.length();
        while (i < n) {
            if (!NetworkRule.isTokenChar(lowerUrl.charAt(i))) {
                i++;
                continue;
            }
            int start = i;
            while (i < n && NetworkRule.isTokenChar(lowerUrl.charAt(i))) {
                i++;
            }
            if (i - start >= 2) {
                tokens.add(lowerUrl.substring(start, i));
            }
        }
        return new ArrayList<>(tokens);
    }

    // CSS selectors of the elements to hide on this host
    public List<String> selectorsFor(String host) {
        Set<String> selectors = new LinkedHashSet<>();
        Set<String> exceptions = new HashSet<>();
        for (CosmeticRule rule : cosmeticRules) {
            if (rule.scriptlet || !rule.appliesTo(host)) {
                continue;
            }
            (rule.exception ? exceptions : selectors).add(rule.selector);
        }
        selectors.removeAll(exceptions);
        return new ArrayList<>(selectors);
    }

    // Scriptlet calls (name followed by arguments) to run on this host
    public List<List<String>> scriptletsFor(String host) {
        Map<String, CosmeticRule> scriptlets = new LinkedHashMap<>();
        Set<String> exceptions = new HashSet<>();
        for (CosmeticRule rule : cosmeticRules) {
            if (!rule.scriptlet || !rule.appliesTo(host)) {
                continue;
            }
            if (rule.exception) {
                if (rule.scriptletArgs.get(0).isEmpty()) {
                    return Collections.emptyList(); // "#@#+js()": no scriptlets on this site
                }
                exceptions.add(rule.scriptletKey());
            } else if (rule.trusted || !requiresTrust(rule.scriptletArgs.get(0))) {
                scriptlets.put(rule.scriptletKey(), rule);
            }
        }
        List<List<String>> calls = new ArrayList<>();
        for (Map.Entry<String, CosmeticRule> entry : scriptlets.entrySet()) {
            if (!exceptions.contains(entry.getKey())) {
                calls.add(entry.getValue().scriptletArgs);
            }
        }
        return calls;
    }

    // Scriptlets that can run arbitrary code or rewrite responses, only allowed from trusted lists
    static boolean requiresTrust(String scriptlet) {
        return scriptlet.startsWith("trusted-") || scriptlet.equals("replace-node-text") || scriptlet.equals("rpnt");
    }
}
