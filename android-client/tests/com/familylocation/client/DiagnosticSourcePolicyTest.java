package com.familylocation.client;

public final class DiagnosticSourcePolicyTest {
    private DiagnosticSourcePolicyTest() {
    }

    public static void main(String[] args) {
        assertTrue(DiagnosticSourcePolicy.isNetworkProbeType("IP"), "IP is a direct network row");
        assertTrue(DiagnosticSourcePolicy.isNetworkProbeType(" webrtc "), "WebRTC is a direct network row");
        assertFalse(DiagnosticSourcePolicy.isNetworkProbeType("gps"), "GPS is not a network row");

        String sameIpFromAnotherProvider = sourceKey("ip", "203.0.113.8", "provider-b", "server-b", 1);
        assertEquals(
            sourceKey("ip", "203.0.113.8", "provider-a", "server-a", 0),
            sameIpFromAnotherProvider,
            "the same detected IP shares one source bundle"
        );
        assertNotEquals(
            sameIpFromAnotherProvider,
            sourceKey("ip", "203.0.113.9", "provider-b", "server-b", 1),
            "different detected IP identities stay distinct"
        );
        assertNotEquals(
            sourceKey("webrtc", "2001:db8::1", "stun-a", "stun:a", 0),
            sourceKey("webrtc", "2001:db8::2", "stun-a", "stun:a", 1),
            "different WebRTC identities stay distinct"
        );
        assertNotEquals(
            sourceKey("webrtc", "2001:db8::1", "stun-a", "stun:a", 0),
            sourceKey("webrtc", "2001:db8::1", "stun-b", "stun:b", 1),
            "the same public IP from different STUN origins stays distinct"
        );
        assertNotEquals(
            sourceKey("gps", "", "高德", "amap", 0),
            sourceKey("gps", "", "美团", "meituan", 1),
            "independent GPS reverse-geocoding providers stay distinct"
        );
        assertTrue(
            DiagnosticSourcePolicy.identitiesCompatible("203.0.113.8", "", "stun:a", "stun:a", true),
            "a matching STUN candidate may inherit its missing network identity"
        );
        assertFalse(
            DiagnosticSourcePolicy.identitiesCompatible("203.0.113.8", "", "stun:a", "stun:b", true),
            "a conflicting STUN-only child must not inherit the parent IP"
        );

        String evidence = DiagnosticSourcePolicy.evidenceKey(
            "203.0.113.8", "高德", "amap", "浙江省杭州市余杭区文一西路969号", "30.1,120.2"
        );
        assertEquals(
            evidence,
            DiagnosticSourcePolicy.evidenceKey(
                " 203.0.113.8 ", "高德", "AMAP", "浙江省杭州市余杭区文一西路969号", "30.1,120.2"
            ),
            "equivalent evidence is deduplicated"
        );
        assertNotEquals(
            evidence,
            DiagnosticSourcePolicy.evidenceKey(
                "203.0.113.8", "美团", "meituan", "浙江省杭州市余杭区文一西路969号", "30.1,120.2"
            ),
            "provider variants remain independently selectable"
        );
        assertNotEquals(
            DiagnosticSourcePolicy.evidenceKey("ab", "c"),
            DiagnosticSourcePolicy.evidenceKey("a", "bc"),
            "length framing prevents concatenation collisions"
        );
    }

    private static String sourceKey(String type, String ip, String provider, String source, int fallbackIndex) {
        return DiagnosticSourcePolicy.sourceMergeKey(
            type,
            ip,
            "",
            "",
            "",
            provider,
            source,
            type,
            source,
            "30.1,120.2",
            fallbackIndex
        );
    }

    private static void assertTrue(boolean value, String label) {
        if (!value) {
            throw new AssertionError(label);
        }
    }

    private static void assertFalse(boolean value, String label) {
        if (value) {
            throw new AssertionError(label);
        }
    }

    private static void assertEquals(String expected, String actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": expected " + expected + ", actual " + actual);
        }
    }

    private static void assertNotEquals(String left, String right, String label) {
        if (left.equals(right)) {
            throw new AssertionError(label + ": both values were " + left);
        }
    }
}
