package me.texy.databindinghunter;

import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementFactory;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlTag;

public class LayoutXmlHunter {

    private final PsiElementFactory mElementFactory;
    private PsiElement mXmlFile;


    public LayoutXmlHunter(PsiElement xmlFile) {
        this.mXmlFile = xmlFile;
        mElementFactory = JavaPsiFacade.getElementFactory(xmlFile.getProject());

    }

    public void hunt() {
        PsiElement layoutElement = mXmlFile.getChildren()[0].getChildren()[1];
        if (layoutElement == null) return;

        PsiElement[] children = layoutElement.getChildren();
        if (children.length < 2 || !children[1].getText().equals("layout")) return;


        for (int i = 0; i < children.length; i++) {
            PsiElement child = children[i];
            if (child instanceof XmlTag && !"data".equals(((XmlTag) child).getName())) {
                if (((XmlTag) layoutElement).getAttributes().length > 0) {
                    for (XmlAttribute attr : ((XmlTag) layoutElement).getAttributes()) {
                        ((XmlTag) child).setAttribute(attr.getName(), attr.getValue());
                    }
                }
                layoutElement.replace(child);
                break;
            }
        }
    }
}
