package com.familylocation.client;

public final class AddressDisplayPolicyTest {
    private AddressDisplayPolicyTest() {
    }

    public static void main(String[] args) {
        assertEquals(
            "中国浙江省杭州市余杭区文一西路969号",
            AddressDisplayPolicy.mostPrecise(
                "浙江省杭州市",
                "中国浙江省杭州市余杭区文一西路969号",
                "203.0.113.8",
                true
            ),
            "structured street address"
        );
        assertEquals(
            "中国浙江省杭州市",
            AddressDisplayPolicy.mostPrecise("杭州市", "中国浙江省杭州市", "", false),
            "structured parent hierarchy"
        );
        assertEquals(
            "杭州市西湖区未解析建筑名",
            AddressDisplayPolicy.mostPrecise("杭州市西湖区未解析建筑名", "中国浙江省杭州市", "", false),
            "explicit unstructured detail"
        );
        assertEquals(
            "浙江省杭州市西湖区文一路969号",
            AddressDisplayPolicy.mostPrecise(
                "浙江省杭州市西湖区文一路969号",
                "中国浙江省杭州市西湖区",
                "",
                true
            ),
            "explicit street beats structured district"
        );
        assertEquals(
            "中国浙江省杭州市余杭区",
            AddressDisplayPolicy.mostPrecise("203.0.113.8", "中国浙江省杭州市余杭区", "203.0.113.8", true),
            "IP placeholder replacement"
        );
    }

    private static void assertEquals(String expected, String actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": expected " + expected + ", actual " + actual);
        }
    }
}
