package cz.martykan.webtube.adblock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class FilterEngineTest {
    private static final int XHR = NetworkRule.TYPE_XHR;
    private static final int SCRIPT = NetworkRule.TYPE_SCRIPT;
    private static final int IMAGE = NetworkRule.TYPE_IMAGE;

    private static FilterEngine engine(boolean trusted, String... lines) throws IOException {
        FilterParser.Result result = FilterParser.parse(new StringReader(String.join("\n", lines)), trusted);
        return new FilterEngine(Collections.singletonList(result));
    }

    private static FilterEngine engine(String... lines) throws IOException {
        return engine(false, lines);
    }

    @Test
    public void hosts() {
        assertEquals("m.youtube.com", Domains.hostOf("https://m.youtube.com/watch?v=x"));
        assertEquals("example.com", Domains.hostOf("http://user@Example.com:8080/a"));
        assertEquals("youtube.com", Domains.registrable("m.youtube.com"));
        assertEquals("example.co.uk", Domains.registrable("a.b.example.co.uk"));
        assertTrue(Domains.matches("m.youtube.com", "youtube.com"));
        assertFalse(Domains.matches("notyoutube.com", "youtube.com"));
        assertTrue(Domains.matches("www.google.com.br", "google.*"));
        assertFalse(Domains.matches("google.example.com", "google.*"));
        assertTrue(Domains.isThirdParty("rr1.googlevideo.com", "m.youtube.com"));
        assertFalse(Domains.isThirdParty("i.youtube.com", "m.youtube.com"));
    }

    @Test
    public void blocksByHostAnchor() throws IOException {
        FilterEngine engine = engine("||youtube.com/api/stats/ads?", "||doubleclick.net^");
        assertTrue(engine.shouldBlock("https://m.youtube.com/api/stats/ads?ver=2&ad_v=1", "m.youtube.com", XHR));
        assertFalse(engine.shouldBlock("https://m.youtube.com/api/stats/qoe?ver=2", "m.youtube.com", XHR));
        assertFalse(engine.shouldBlock("https://notyoutube.com/api/stats/ads?x", "m.youtube.com", XHR));
        assertTrue(engine.shouldBlock("https://static.doubleclick.net/instream/ad_status.js", "m.youtube.com", SCRIPT));
        assertTrue(engine.shouldBlock("https://doubleclick.net", "m.youtube.com", SCRIPT));
        assertFalse(engine.shouldBlock("https://doubleclick.net.example.com/", "m.youtube.com", SCRIPT));
    }

    @Test
    public void plainPatternsAndWildcards() throws IOException {
        FilterEngine engine = engine("/youtubeVideoAnalytic.js", "||youtube.com/get_video_info?*adunit$~third-party", "-ad-banner.");
        assertTrue(engine.shouldBlock("https://cdn.example.com/x/youtubeVideoAnalytic.js", "example.com", SCRIPT));
        assertTrue(engine.shouldBlock("https://www.youtube.com/get_video_info?x=1&adunit=2", "www.youtube.com", XHR));
        assertFalse(engine.shouldBlock("https://www.youtube.com/get_video_info?x=1&adunit=2", "example.com", XHR));
        assertTrue(engine.shouldBlock("https://example.com/img/top-ad-banner.png", "example.com", IMAGE));
    }

    @Test
    public void optionsAndExceptions() throws IOException {
        FilterEngine engine = engine(
                "||googlesyndication.com^$domain=blogto.com|youtube.com",
                "||tracker.example^$third-party,script",
                "||ads.example^",
                "@@||ads.example/allowed/",
                "||forced.example^$important",
                "@@||forced.example^",
                "||images.example^$~image");
        assertTrue(engine.shouldBlock("https://pagead2.googlesyndication.com/x.js", "m.youtube.com", SCRIPT));
        assertFalse(engine.shouldBlock("https://pagead2.googlesyndication.com/x.js", "example.com", SCRIPT));
        assertTrue(engine.shouldBlock("https://tracker.example/t.js", "m.youtube.com", SCRIPT));
        assertFalse(engine.shouldBlock("https://tracker.example/t.png", "m.youtube.com", IMAGE));
        assertFalse(engine.shouldBlock("https://tracker.example/t.js", "tracker.example", SCRIPT));
        assertTrue(engine.shouldBlock("https://ads.example/banner.js", "m.youtube.com", SCRIPT));
        assertFalse(engine.shouldBlock("https://ads.example/allowed/banner.js", "m.youtube.com", SCRIPT));
        assertTrue(engine.shouldBlock("https://forced.example/a.js", "m.youtube.com", SCRIPT));
        assertFalse(engine.shouldBlock("https://images.example/a.png", "m.youtube.com", IMAGE));
        assertTrue(engine.shouldBlock("https://images.example/a.js", "m.youtube.com", SCRIPT));
    }

    // EasyPrivacy blocks every third-party ping; the video stream (an XHR to googlevideo.com) must load
    @Test
    public void typeOnlyRulesDoNotBlockOtherTypes() throws IOException {
        FilterEngine engine = engine("*$ping,third-party");
        String video = "https://rr2---sn-x.googlevideo.com/videoplayback?expire=1&id=o-x";
        assertFalse(engine.shouldBlock(video, "m.youtube.com", XHR));
        assertTrue(engine.shouldBlock(video, "m.youtube.com", NetworkRule.TYPE_PING));
    }

    @Test
    public void neverBlocksThePageWithoutDocumentOption() throws IOException {
        FilterEngine engine = engine("||ads.example^", "@@||allowed.example^$document");
        assertFalse(engine.shouldBlock("https://ads.example/", "ads.example", NetworkRule.TYPE_DOCUMENT));
        assertTrue(engine.isPageAllowlisted("https://www.allowed.example/page"));
        assertFalse(engine.isPageAllowlisted("https://ads.example/page"));
    }

    @Test
    public void skipsUnsupportedRules() throws IOException {
        FilterParser.Result result = FilterParser.parse(new StringReader(String.join("\n",
                "/^https?:\\/\\/[a-z]+\\.example\\//",
                "||example.com^$removeparam=utm_source",
                "example.com##div:has-text(Ad)",
                "example.com#?#div:-abp-has(.ad)",
                "example.com##^script:has-text(ads)",
                "##+js(set, generic, true)",
                "! comment",
                "[Adblock Plus 2.0]",
                "||kept.example^")), false);
        assertEquals(1, result.networkRules.size());
        assertEquals(0, result.cosmeticRules.size());
        assertEquals(6, result.unsupported);
        assertNull(NetworkRule.parse("||example.com^$popup"));
    }

    @Test
    public void preprocessor() throws IOException {
        FilterEngine engine = engine(
                "!#if env_mobile",
                "||mobile.example^",
                "!#else",
                "||desktop.example^",
                "!#endif",
                "!#if !env_chromium || env_firefox",
                "||firefox.example^",
                "!#endif");
        assertTrue(engine.shouldBlock("https://mobile.example/", "x.com", SCRIPT));
        assertFalse(engine.shouldBlock("https://desktop.example/", "x.com", SCRIPT));
        assertFalse(engine.shouldBlock("https://firefox.example/", "x.com", SCRIPT));
        assertTrue(FilterParser.evaluate("(env_chromium && !cap_html_filtering)"));
    }

    @Test
    public void elementHiding() throws IOException {
        FilterEngine engine = engine(
                "m.youtube.com##ytm-rich-item-renderer > ad-slot-renderer",
                "youtube.com###sticker-layer",
                "youtube.com##.generic-on-youtube",
                "ads.google.com,youtube.com#@#.generic-on-youtube",
                "~m.youtube.com,youtube.com##.desktop-only",
                "##.generic-everywhere");
        List<String> mobile = engine.selectorsFor("m.youtube.com");
        assertEquals(Arrays.asList("ytm-rich-item-renderer > ad-slot-renderer", "#sticker-layer"), mobile);
        assertEquals(Arrays.asList("#sticker-layer", ".desktop-only"), engine.selectorsFor("www.youtube.com"));
    }

    @Test
    public void scriptletArguments() {
        assertEquals(Arrays.asList("set", "ytInitialPlayerResponse.playerAds", "undefined"),
                CosmeticRule.parseScriptletArgs("set, ytInitialPlayerResponse.playerAds, undefined"));
        assertEquals(Arrays.asList("trusted-rpfr", "\"adPlacements\"", "\"no_ads\"", "player?"),
                CosmeticRule.parseScriptletArgs("trusted-rpfr, '\"adPlacements\"', '\"no_ads\"', player?"));
        assertEquals(Arrays.asList("json-prune-fetch-response", "a b", "", "propsToMatch", "/x/"),
                CosmeticRule.parseScriptletArgs("json-prune-fetch-response, a b, , propsToMatch, /x/"));
        assertEquals(Arrays.asList("rpnt", "script", "/a{2,4}/"),
                CosmeticRule.parseScriptletArgs("rpnt, script, /a{2\\,4}/"));
    }

    @Test
    public void scriptlets() throws IOException {
        String[] lines = {
                "m.youtube.com,www.youtube.com##+js(set, ytInitialPlayerResponse.adPlacements, undefined)",
                "youtube.com##+js(json-prune, playerResponse.adPlacements adPlacements)",
                "www.youtube.com##+js(trusted-rpfr, '\"adSlots\"', '\"no_ads\"', player?)",
                "www.youtube.com##+js(rpnt, script, a, b)",
                "youtube.com#@#+js(json-prune, playerResponse.adPlacements adPlacements)",
                "youtube.com##+js(nostif, adblock)",
                "music.youtube.com#@#+js()",
                "music.youtube.com##+js(nostif, adblock)"};
        FilterEngine untrusted = engine(false, lines);
        assertEquals(Arrays.asList(
                Arrays.asList("set", "ytInitialPlayerResponse.adPlacements", "undefined"),
                Arrays.asList("nostif", "adblock")), untrusted.scriptletsFor("m.youtube.com"));
        assertEquals(2, untrusted.scriptletsFor("www.youtube.com").size());
        assertEquals(4, engine(true, lines).scriptletsFor("www.youtube.com").size());
        assertTrue(untrusted.scriptletsFor("music.youtube.com").isEmpty());
    }

    @Test
    public void scriptQuoting() {
        assertEquals("\"a\\\"b\\\\c\\n\\u003c/script\\u003e\\u2028\"",
                ScriptBuilder.quote("a\"b\\c\n</script> "));
        assertEquals("[\"x\", \"\"]", ScriptBuilder.array(Arrays.asList("x", "")));
    }

    @Test
    public void builtScript() throws IOException {
        FilterEngine engine = engine("m.youtube.com##+js(set, a.b, undefined)", "m.youtube.com##.ad");
        String script = ScriptBuilder.build("(function () { return {}; })();", engine, "m.youtube.com");
        assertTrue(script.contains("var scriptlets = (function () { return {}; })();"));
        assertTrue(script.contains("scriptlets.run(\"set\", [\"a.b\", \"undefined\"]);"));
        assertTrue(script.contains("scriptlets.hide([\".ad\"]);"));
        assertNull(ScriptBuilder.build("x", engine, "example.com"));
    }

    @Test
    public void manyRulesStayFast() throws IOException {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 50000; i++) {
            lines.add("||ads" + i + ".example^");
            lines.add("/banner" + i + "/*");
        }
        FilterEngine engine = engine(lines.toArray(new String[0]));
        long start = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            engine.shouldBlock("https://rr" + i + "---sn-abc.googlevideo.com/videoplayback?expire=1&id=" + i, "m.youtube.com", NetworkRule.TYPE_MEDIA);
        }
        long millis = (System.nanoTime() - start) / 1_000_000;
        assertTrue("1000 lookups took " + millis + " ms", millis < 2000);
        assertTrue(engine.shouldBlock("https://ads4999.example/x", "m.youtube.com", SCRIPT));
        assertTrue(engine.shouldBlock("https://example.com/banner123/x.png", "m.youtube.com", IMAGE));
    }
}
