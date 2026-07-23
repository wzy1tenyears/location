package com.familylocation.client;

final class AddressPrecisionPolicy {
    private AddressPrecisionPolicy() {
    }

    static int score(
        String country,
        String region,
        String city,
        String district,
        String street,
        String address,
        String detail,
        String poi
    ) {
        String combined = text(address) + text(detail) + text(street);
        int score = 0;
        if (!text(country).isEmpty()) score = Math.max(score, 1);
        if (!text(region).isEmpty()) score = Math.max(score, 2);
        if (!text(city).isEmpty()) score = Math.max(score, 3);
        if (!text(district).isEmpty()) score = Math.max(score, 4);
        if (!text(street).isEmpty() || combined.matches(".*(街道|镇|乡|路|街|大道|巷|弄).*")) score = Math.max(score, 5);
        if (!text(poi).isEmpty() || combined.matches(".*(小区|花园|家园|公寓|大厦|广场|中心|园区|学校|医院|写字楼|商务|住宅区|酒店|商场|市场|超市|银行|地铁站|车站|停车场|便利店|餐厅|门店|馆|苑|府|轩|阁).*")) score = Math.max(score, 6);
        if (combined.matches(".*(\\d+\\s*号|[一二三四五六七八九十\\d]+\\s*(栋|幢|座|单元|楼|层|室)|[A-Z]\\s*\\d).*")) score = Math.max(score, 7);
        return score;
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
