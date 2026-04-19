package com.exo.adblock;

public final class AdBlockHolder {
    private static boolean adBlockEnabled = true;

    public static void setAdBlockEnabled(boolean enabled) {
        adBlockEnabled = enabled;
    }

    public static boolean isAdBlockEnabled() {
        return adBlockEnabled;
    }
}