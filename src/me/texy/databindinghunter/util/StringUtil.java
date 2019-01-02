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
        return contactLastNumWithUnderline(CaseFormat.UPPER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, camel));
    }

    public static String getClassPkgFromAbsDir(String path) {
        int index = path.indexOf("/src/main/java");
        if (index != -1) {
            return path.substring(index + 14)
                    .replaceAll(".java", "")
                    .replaceFirst("/", "")
                    .replaceAll("/", ".");
        }
        return path;
    }

    // layout_demo01 ->layout_demo_01
    public static String contactLastNumWithUnderline(String s) {
        int start = s.length();
        int end = -1;
        for (int i = s.length() - 1; i >= 0; i--) {
            if (s.charAt(i) >= '0' && s.charAt(i) <= '9') {
                start = i;
                if (end == -1) {
                    end = i + 1;
                }
            } else if (end != -1) {
                break;
            }
        }
        if (start < s.length()) {
            return s.substring(0, start)
                    .concat("_")
                    .concat(s.substring(start, end))
                    .concat(end == s.length() ? "" : "_" + s.substring(end, s.length()));
        }
        return s;
    }
}
