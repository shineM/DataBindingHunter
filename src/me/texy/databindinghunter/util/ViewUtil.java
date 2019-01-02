package me.texy.databindinghunter.util;

public class ViewUtil {
    public static String getViewClassPath(String type) {
        if (type.equals("View") || type.equals("ViewStub")) {
            return "android.view." + type;
        } else if (!type.contains(".")) {
            return "android.widget." + type;
        }
        return type;
    }
}
