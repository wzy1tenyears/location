package com.familylocation.net;

import android.graphics.Color;

public final class Material3Tokens {
    private Material3Tokens() {
    }

    public static int surface(boolean dark) { return dark ? Color.rgb(17, 24, 22) : Color.rgb(238, 243, 241); }
    public static int surfaceContainer(boolean dark) { return dark ? Color.rgb(25, 34, 32) : Color.WHITE; }
    public static int onSurface(boolean dark) { return dark ? Color.rgb(238, 246, 243) : Color.rgb(23, 34, 32); }
    public static int onSurfaceVariant(boolean dark) { return dark ? Color.rgb(167, 184, 179) : Color.rgb(100, 115, 111); }
    public static int primary(boolean dark) { return dark ? Color.rgb(54, 182, 156) : Color.rgb(13, 95, 84); }
    public static int onPrimary(boolean dark) { return dark ? Color.rgb(3, 31, 26) : Color.WHITE; }
    public static int outline(boolean dark) { return dark ? Color.rgb(46, 61, 57) : Color.rgb(217, 226, 223); }
    public static int ripple(boolean dark) { return dark ? Color.argb(48, 139, 230, 211) : Color.argb(38, 13, 95, 84); }
    public static int primaryContainer(boolean dark) { return dark ? Color.rgb(25, 55, 49) : Color.rgb(228, 241, 238); }
}
