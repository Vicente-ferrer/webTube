package cz.martykan.webtube;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SponsorBlockTest {
    @Test
    public void hashPrefixIsTheStartOfTheSha256() {
        assertEquals("5f6b", SponsorBlock.hashPrefix("dQw4w9WgXcQ"));
    }

    @Test
    public void keepsOnlyTheSegmentsOfTheVideo() throws Exception {
        String response = "["
                + "{\"videoID\":\"otherVideo1\",\"segments\":[{\"segment\":[1,2],\"category\":\"sponsor\",\"actionType\":\"skip\"}]},"
                + "{\"videoID\":\"dQw4w9WgXcQ\",\"segments\":["
                + "{\"segment\":[10.5,42],\"category\":\"sponsor\",\"actionType\":\"skip\",\"UUID\":\"a\"},"
                + "{\"segment\":[60,60],\"category\":\"selfpromo\",\"actionType\":\"skip\"},"
                + "{\"segment\":[70,80],\"category\":\"sponsor\",\"actionType\":\"mute\"},"
                + "{\"segment\":[90,95.25],\"category\":\"interaction\"}]}]";
        assertEquals("[[10.5,42,\"sponsor\"],[90,95.25,\"interaction\"]]",
                SponsorBlock.parse("dQw4w9WgXcQ", response));
    }

    @Test
    public void noSegmentsForAnotherVideo() throws Exception {
        assertEquals("[]", SponsorBlock.parse("dQw4w9WgXcQ",
                "[{\"videoID\":\"otherVideo1\",\"segments\":[{\"segment\":[1,2],\"category\":\"sponsor\"}]}]"));
    }
}
