package me.texy.databindinghunter;

import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlDocument;
import com.intellij.psi.xml.XmlTag;
import me.texy.databindinghunter.util.StringUtil;
import org.apache.commons.lang.StringUtils;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public class JavaBindingHunter {

    private static final String CLASS_PATH_DATA_BINDING_UTIL = "android.databinding.DataBindingUtil";
    private static final String CLASS_NAME_DATA_BINDING_UTIL = "DataBindingUtil";
    private static final String CLASS_PATH_VIEW_DATA_BINDING = "android.databinding.ViewDataBinding";
    private PsiClass mClass;
    private PsiElementFactory mElementFactory;
    private Set<String> mDataBindingImports = new HashSet<>();
    private HashMap<String, String> mViewFields = new HashMap<>();

    private Map<String, Map<String, String>> mBindingViewIdsMap = new HashMap<>();

    public JavaBindingHunter(PsiClass psiClass) {
        this.mClass = psiClass;
        mElementFactory = JavaPsiFacade.getElementFactory(mClass.getProject());
    }

    public void hunt() {
        if (mClass == null) return;

        JavaCodeStyleManager.getInstance(mClass.getProject()).shortenClassReferences(mClass);

        searchFromImports();

        if (mDataBindingImports.size() > 0) {
            startRealHunt();
        }
    }

    private void searchFromImports() {
        PsiFile psiFile = mClass.getContainingFile();
        Project project = mClass.getProject();

        if (!(psiFile instanceof PsiJavaFile)) {
            return;
        }
        PsiJavaFile psiJavaFile = (PsiJavaFile) psiFile;
        PsiImportList importList = psiJavaFile.getImportList();

        if (importList == null || importList.getImportStatements().length <= 0) {
            return;
        }

        for (int i = 0; i < importList.getImportStatements().length; i++) {
            PsiImportStatement importStatement = importList.getImportStatements()[i];
            String importClass = importStatement.getQualifiedName();
            if (importClass == null) continue;

            if (importClass.equals(CLASS_PATH_DATA_BINDING_UTIL)) {
                mDataBindingImports.add(importClass);
                importStatement.delete();
                continue;
            }

            PsiClass psiClass = JavaPsiFacade.getInstance(project)
                    .findClass(importClass, GlobalSearchScope.projectScope(project));
            if (psiClass == null) continue;

            if (psiClass.getSuperClass() != null
                    && CLASS_PATH_VIEW_DATA_BINDING.equals(psiClass.getSuperClass().getQualifiedName())) {
                mDataBindingImports.add(importClass);
                importStatement.delete();
            }
        }
    }

    private void startRealHunt() {
        PsiMethod[] methods = mClass.getMethods();
        for (PsiMethod method : methods) {
            PsiCodeBlock methodBody = method.getBody();
            if (methodBody == null) continue;
            if (!isTextContainsDataBindingCall(method.getText())) continue;

            PsiStatement[] statements = methodBody.getStatements();
            for (PsiStatement statement : statements) {
                if (isTextContainsDataBindingCall(statement.getText())) {
                    replaceDataBindingElementRecursive(statement);
                }
            }
        }
    }

    private boolean isTextContainsDataBindingCall(String text) {
        for (String dataBindingClass : mDataBindingImports) {
            if (text.contains(StringUtil.getClassNameFromPath(dataBindingClass))) {
                return true;
            }
        }
        return false;
    }

    private void replaceDataBindingElementRecursive(PsiElement statement) {
        if (statement instanceof PsiMethodCallExpression) {
            PsiMethodCallExpression dataBindingCall = (PsiMethodCallExpression) statement;

            for (String classPath : mDataBindingImports) {
                if (statement.getText().contains(StringUtil.getClassNameFromPath(classPath))) {
                    replaceViewBindingMethodCall(dataBindingCall, classPath);
                    break;
                }
            }
        }

        PsiElement[] elements = statement.getChildren();
        for (PsiElement element : elements) {
            replaceDataBindingElementRecursive(element);
        }
    }

    private void replaceViewBindingMethodCall(PsiMethodCallExpression dataBindingCall, String className) {
        // 1) replace 「FooBinding binding」 with 「View binding」
        replaceDeclareTypeWithView(dataBindingCall.getParent());

        // 2) replace DataBindingUtil/FooBinding.bind/inflate with inflater.inflate
        replaceBindingMethodCallExpression(dataBindingCall, className);

    }

    private void replaceDeclareTypeWithView(PsiElement dataBindingCallParent) {
        PsiType viewBindingType = null;
        PsiElement sourceDeclare = null;

        // mBinding = DataBindingUtil.bind
        if (dataBindingCallParent instanceof PsiAssignmentExpression) {
            PsiAssignmentExpression assignmentExpression = (PsiAssignmentExpression) dataBindingCallParent;
            PsiReference reference = assignmentExpression.getLExpression().getReference();
            viewBindingType = assignmentExpression.getType();

            if (reference != null && reference.resolve() != null) {
                // field declare
                sourceDeclare = reference.resolve();
            }
            replaceAllViewRefsFromFieldBinding(viewBindingType, reference);
        }
        // FooViewBinding binding = DataBindingUtil.bind
        else if (dataBindingCallParent instanceof PsiLocalVariable) {
            sourceDeclare = dataBindingCallParent;
            viewBindingType = ((PsiLocalVariable) dataBindingCallParent).getType();
        }

        if (sourceDeclare != null && viewBindingType != null) {
            for (PsiElement e : sourceDeclare.getChildren()) {
                if (e instanceof PsiTypeElement && e.getText().contains(viewBindingType.getPresentableText())) {
                    addImport("android.view.View");
                    e.replace(mElementFactory.createTypeElementFromText("View", null));
                    break;
                }
            }
        }
    }

    /**
     * mViewBinding.xxx -> mXxxView
     * mViewBinding.getRoot -> mViewBinding
     *
     * @param viewBindingType
     * @param viewReference
     */
    private void replaceAllViewRefsFromFieldBinding(PsiType viewBindingType, PsiReference viewReference) {
        Collection<PsiReference> references = ReferencesSearch.search(viewReference.resolve(), GlobalSearchScope.projectScope(mClass.getProject())).findAll();
        for (PsiReference r : references) {
            PsiElement parent = r.getElement().getParent();
            if (parent instanceof PsiReferenceExpression) {
                PsiElement integrity = parent.getParent();
                if (integrity.getText().endsWith(".getRoot()")) {
                    String newText = r.getElement().getText() + "View";
                    integrity.replace(mElementFactory.createReferenceFromText(newText, null));
                } else if (integrity.getText().matches(".*.set.*\\)$")) {
//                        integrity.delete();
                } else {
                    String newField = createViewField(viewBindingType, parent);
                    if (newField != null) {
                        parent.replace(mElementFactory.createReferenceFromText(newField, null));
                    }
                }
            } else if (parent instanceof PsiBinaryExpression) {

            }
        }
    }

    private String createViewField(PsiType viewBindingClass, PsiElement originBindingRef) {
        PsiElement[] children = originBindingRef.getChildren();
        for (PsiElement child : children) {
            if (child instanceof PsiIdentifier) {
                String text = child.getText();
                if (!mViewFields.containsKey(text)) {
                    String newField;
                    char firstChar = text.charAt(0);
                    newField = "m" + text.replaceFirst(String.valueOf(firstChar), StringUtils.upperCase(String.valueOf(firstChar)));
                    if (!newField.endsWith("View")) {
                        newField += "View";
                    }
                    if (isFieldExist(newField)) {
                        newField += "2";
                    }
                    String type = getViewTypeFromXml(viewBindingClass.getPresentableText(), StringUtil.formatCamelToUnderline(text));
                    mClass.add(mElementFactory.createFieldFromText("\n    private " + type + " " + newField + ";", mClass));
                    mViewFields.put(text, newField);
                    return newField;
                } else {
                    return mViewFields.get(text);
                }
            }
        }
        return null;
    }

    @Nullable
    private String getViewTypeFromXml(String viewBindingType, String id) {
        if (mBindingViewIdsMap.containsKey(viewBindingType)) {

        } else {
            String bindingLayoutName = viewBindingType.substring(0, viewBindingType.lastIndexOf("Binding"));
            String xmlName = StringUtil.formatCamelToUnderline(bindingLayoutName).concat(".xml");
            PsiFile[] psiFiles = FilenameIndex.getFilesByName(mClass.getProject(), xmlName, GlobalSearchScope.allScope(mClass.getProject()));
            PsiFile xmlFile = psiFiles[0];
            mBindingViewIdsMap.put(viewBindingType, getIdsFromLayoutXml(xmlFile));
        }
        return mBindingViewIdsMap.get(viewBindingType).get(id);
    }

    private Map<String, String> getIdsFromLayoutXml(PsiElement xmlFile) {
        HashMap<String, String> map = new HashMap<>();

        for (PsiElement element : xmlFile.getChildren()) {
            if (element instanceof XmlDocument && ((XmlDocument) element).getRootTag().getName().equals("layout")) {
                for (PsiElement tag : element.getChildren()) {
                    if (tag instanceof XmlTag && ((XmlTag) tag).getName().equals("layout")) {
                        getIdsFromAttrs(tag, map);
                    }
                }
            }
        }
        return map;
    }

    private void getIdsFromAttrs(PsiElement tag, HashMap<String, String> map) {
        if (tag instanceof XmlTag) {
            XmlAttribute id = ((XmlTag) tag).getAttribute("android:id", null);
            if (id != null && id.getValue() != null) {
                map.put(id.getValue().substring(id.getValue().indexOf("id/") + 3), ((XmlTag) tag).getName());
            }
            for (PsiElement child : tag.getChildren()) {
                getIdsFromAttrs(child, map);
            }
        }
    }

    private boolean isFieldExist(String newField) {
        PsiField[] fields = mClass.getFields();

        for (PsiField field : fields) {
            if (field.getText().equals(newField)) {
                return true;
            }
        }
        return false;
    }

    private void replaceBindingMethodCallExpression(PsiMethodCallExpression dataBindingCall, String subclass) {
        String plainText = dataBindingCall.getText();

        String toReplace = StringUtil.getClassNameFromPath(subclass);
        if (plainText.contains(subclass)) {
            toReplace = subclass;
        }
        PsiExpression newMethodCallElement = null;
        if (plainText.contains(toReplace + ".bind")) {
            PsiExpression[] expressions = dataBindingCall.getArgumentList().getExpressions();
            if (expressions.length != 1) {
                System.out.print("bind method's parameters not invalid,only support bind(View view)");
                return;
            }
            newMethodCallElement = mElementFactory.createExpressionFromText(expressions[0].getText(), null);
        } else if (plainText.contains(toReplace + ".inflate")) {
            PsiExpression[] expressions = dataBindingCall.getArgumentList().getExpressions();
            StringBuilder inflateText = new StringBuilder(expressions[0].getText() + ".inflate(");

            if (CLASS_NAME_DATA_BINDING_UTIL.equals(toReplace)) {
                for (int i = 1; i < expressions.length; i++) {
                    PsiExpression param = expressions[i];
                    if (i == expressions.length - 1) {
                        inflateText.append(param.getText()).append(")");
                    } else {
                        inflateText.append(param.getText()).append(", ");
                    }
                }
            } else {
                if (expressions.length != 1 && expressions.length != 3) {
                    System.out.print("inflate method's parameters not invalid,only support:" +
                            " inflate(@NonNull LayoutInflater inflater, @Nullable ViewGroup root, boolean attachToRoot) and" +
                            " inflate(@NonNull LayoutInflater inflater)");
                    return;
                }
                String bindingLayoutName = StringUtil.getClassNameFromPath(toReplace)
                        .substring(0, StringUtil.getClassNameFromPath(toReplace).lastIndexOf("Binding"));
                String layoutResource = "R.layout." + StringUtil.formatCamelToUnderline(bindingLayoutName);
                if (expressions.length == 1) {
                    inflateText.append(layoutResource).append("null, false)");
                } else {
                    inflateText.append(layoutResource).append(", ")
                            .append(expressions[1].getText()).append(", ")
                            .append(expressions[2].getText()).append(")");
                }
            }
            newMethodCallElement = mElementFactory.createExpressionFromText(inflateText.toString(), null);
        }
        if (newMethodCallElement != null) {
            dataBindingCall.replace(newMethodCallElement);
//            newMethodCallElement.
        }

    }

    private void addImport(String packagePath) {
        PsiFile psiFile = mClass.getContainingFile();

        if (!(psiFile instanceof PsiJavaFile)) {
            return;
        }
        PsiJavaFile psiJavaFile = (PsiJavaFile) psiFile;
        PsiImportList importList = psiJavaFile.getImportList();

        if (importList != null && importList.getImportStatements().length > 0) {
            for (PsiImportStatement s : importList.getImportStatements()) {
                if (s.getQualifiedName().equals(packagePath)) {
                    return;
                }
            }
        }
        importList.add(mElementFactory.createImportStatementOnDemand(packagePath));
    }

}
