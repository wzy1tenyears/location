package com.familylocation.client;

public final class AddressPrecisionPolicyTest {
    private AddressPrecisionPolicyTest() {
    }

    public static void main(String[] args) {
        int city = score("中国", "广东省", "深圳市", "", "", "广东省深圳市", "", "");
        int district = score("中国", "广东省", "深圳市", "南山区", "", "广东省深圳市南山区", "", "");
        int street = score("中国", "广东省", "深圳市", "南山区", "粤海街道", "", "", "");
        int building = score("中国", "广东省", "深圳市", "南山区", "粤海街道", "科技园 1 号 A 座", "", "科技园");
        assertTrue(district > city, "district candidate must outrank city-only candidate");
        assertTrue(street > district, "street candidate must outrank district-only candidate");
        assertTrue(building > street, "building candidate must outrank street-only candidate");
    }

    private static int score(String country, String region, String city, String district, String street, String address, String detail, String poi) {
        return AddressPrecisionPolicy.score(country, region, city, district, street, address, detail, poi);
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
