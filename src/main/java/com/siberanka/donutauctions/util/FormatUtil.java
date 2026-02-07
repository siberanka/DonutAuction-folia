package com.siberanka.donutauctions.util;

import java.time.Duration;

public final class FormatUtil {

    private FormatUtil() {
    }

    public static String humanDuration(Duration duration) {
        if (duration.isNegative() || duration.isZero()) {
            return "0m";
        }

        long seconds = duration.getSeconds();
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long minutes = (seconds % 3600) / 60;

        if (days > 0) {
            return days + "d " + hours + "h";
        }
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        return minutes + "m";
    }
}