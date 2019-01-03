package me.texy.databindinghunter;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlDocument;
import com.intellij.psi.xml.XmlTag;
import kotlin.Pair;
import me.texy.databindinghunter.util.StringUtil;

import java.util.HashMap;
import java.util.Map;


public class LayoutXmlInfo {

    // <demoTextView,Pair<demo_text,TextView>>
    private Map<String, Pair<String, String>> bindingViewIdsMap = new HashMap<>();

    private PsiFile xmlFile;

    public LayoutXmlInfo(PsiFile xmlFile) {
        this.xmlFile = xmlFile;
        setupIds();
    }

    private void setupIds() {
        for (PsiElement element : xmlFile.getChildren()) {
            if (element instanceof XmlDocument) {
                for (PsiElement tag : element.getChildren()) {
                    getIdsFromAttrs(tag);
                }
            }
        }
    }

    private void getIdsFromAttrs(PsiElement tag) {
        if (tag instanceof XmlTag) {
            XmlAttribute id = ((XmlTag) tag).getAttribute("android:id", null);
            if (id != null && id.getValue() != null) {
                String idName = id.getValue().substring(id.getValue().indexOf("id/") + 3);
                bindingViewIdsMap.put(StringUtil.formatUnderlineToLowerCamel(idName), new Pair<>(idName, ((XmlTag) tag).getName()));
            }
            for (PsiElement child : tag.getChildren()) {
                getIdsFromAttrs(child);
            }
        }
    }

    public PsiFile getXmlFile() {
        return xmlFile;
    }

    public String getIdByViewRefName(String viewRefName) {
        if (!bindingViewIdsMap.containsKey(viewRefName)) {
            return null;
        }
        return bindingViewIdsMap.get(viewRefName).getFirst();
    }

    public String getViewTypeByViewRefName(String viewRefName) {
        if (!bindingViewIdsMap.containsKey(viewRefName)) {
            return null;
        }
        return bindingViewIdsMap.get(viewRefName).getSecond();
    }
}
