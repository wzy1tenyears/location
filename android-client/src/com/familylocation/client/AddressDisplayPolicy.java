package com.familylocation.client;

final class AddressDisplayPolicy {
    private AddressDisplayPolicy() {
    }

    static String mostPrecise(String explicit, String structured, String ip, boolean structuredHasLocalDetail) {
        String explicitText = clean(explicit);
        String structuredText = clean(structured);
        String ipText = clean(ip);
        if (!structuredText.isEmpty() && (explicitText.isEmpty() || explicitText.equals(ipText))) {
            return structuredText;
        }
        if (!structuredText.isEmpty() && !explicitText.isEmpty()) {
            int structuredPrecision = precision(structuredText);
            int explicitPrecision = precision(explicitText);
            if (structuredPrecision > explicitPrecision
                || (structuredPrecision == explicitPrecision
                    && (compact(structuredText).contains(compact(explicitText))
                        || (structuredHasLocalDetail && structuredText.length() > explicitText.length()
                            && !compact(explicitText).contains(compact(structuredText)))))) {
                return structuredText;
            }
        }
        if (!explicitText.isEmpty()) {
            return explicitText;
        }
        if (!structuredText.isEmpty()) {
            return structuredText;
        }
        return ipText.isEmpty() ? "未知" : ipText;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String compact(String value) {
        return clean(value).replaceAll("\\s+", "");
    }

    private static int precision(String value) {
        String text = compact(value);
        int score = 0;
        if (!text.isEmpty()) score = 1;
        if (text.matches(".*(省|自治区|特别行政区).*")) score = Math.max(score, 2);
        if (text.matches(".*(市|自治州|地区|盟).*")) score = Math.max(score, 3);
        if (text.matches(".*(区|县|旗).*")) score = Math.max(score, 4);
        if (text.matches(".*(街道|镇|乡|路|街|大道|巷|弄).*")) score = Math.max(score, 5);
        if (text.matches(".*(小区|花园|家园|公寓|大厦|广场|中心|园区|学校|医院|写字楼|住宅区|酒店|商场|市场|超市|银行|地铁站|车站|停车场|便利店|餐厅|门店).*")) score = Math.max(score, 6);
        if (text.matches(".*(\\d+号|[一二三四五六七八九十\\d]+(栋|幢|座|单元|楼|层|室)|[A-Z]\\d).*")) score = Math.max(score, 7);
        return score;
    }
}
