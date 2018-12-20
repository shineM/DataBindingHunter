package me.texy.databindinghunter.util;

import com.google.common.base.CaseFormat;

public class StringUtil {
    public static String getClassNameFromPath(String path) {
        int lastDotIndex = path.lastIndexOf('.');
        if (lastDotIndex != -1) {
            return path.substring(lastDotIndex + 1);
        }
        return path;
    }

    public static String formatCamelToUnderline(String camel) {
        return CaseFormat.UPPER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, camel);
    }
}
