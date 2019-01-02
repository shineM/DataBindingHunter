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
import me.texy.databindinghunter.util.ViewUtil;
import org.apache.commons.lang.StringUtils;
import org.apache.http.util.TextUtils;
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

    public boolean hunt() {
        if (mClass == null) return false;

        JavaCodeStyleManager.getInstance(mClass.getProject()).shortenClassReferences(mClass);

        searchFromImports();

        if (mDataBindingImports.size() > 0) {
            startHuntFromImport();
            return true;
        }
        return false;
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

    private void startHuntFromImport() {
        PsiMethod[] methods = mClass.getMethods();
        for (PsiMethod method : methods) {
            PsiCodeBlock methodBody = method.getBody();
            if (methodBody == null) continue;
            if (!isTextContainsDataBindingCall(method.getText())) continue;

            PsiStatement[] statements = methodBody.getStatements();
            for (PsiStatement statement : statements) {
                replaceDataBindingElementRecursive(statement);
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
        if (!isTextContainsDataBindingCall(statement.getText())) {
            return;
        }
        if (statement instanceof PsiMethodCallExpression) {
            PsiMethodCallExpression dataBindingCall = (PsiMethodCallExpression) statement;

            for (String classPath : mDataBindingImports) {
                if (statement.getText().startsWith(StringUtil.getClassNameFromPath(classPath))
                        || statement.getText().startsWith(classPath)) {
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

            if (reference != null && reference.resolve() instanceof PsiField) {
                // field declare
                sourceDeclare = reference.resolve();

                replaceAllViewRefsFromFieldBinding(viewBindingType, (PsiField) sourceDeclare, dataBindingCallParent);
            }
        }
        // FooViewBinding binding = DataBindingUtil.bind
        else if (dataBindingCallParent instanceof PsiLocalVariable) {
            sourceDeclare = dataBindingCallParent;
            viewBindingType = ((PsiLocalVariable) dataBindingCallParent).getType();

            replaceAllViewRefsFromLocalBinding(viewBindingType, (PsiLocalVariable) dataBindingCallParent);
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
     * localBinding.xxx -> xxxView
     */
    private void replaceAllViewRefsFromLocalBinding(PsiType viewBindingType, PsiLocalVariable localVariable) {
        PsiElement parent = localVariable;
        String variableName = localVariable.getName();

        while (parent != null) {
            if (parent instanceof PsiCodeBlock) {
                HashMap<String, String> currentFDVBIs = new HashMap<>();
                for (PsiStatement e : ((PsiCodeBlock) parent).getStatements()) {
                    if (e.getText().contains(variableName)
                            && !e.getText().contains(CLASS_NAME_DATA_BINDING_UTIL)
                            && !e.getText().contains(localVariable.getType().getPresentableText())) {

                        String replace = variableName;
                        String toReplace = variableName;
                        if (e.getText().contains(variableName + ".getRoot()")) {
                            toReplace = variableName + ".getRoot()";
                        } else if (e.getText().contains(variableName + ".")) {
                            String viewRef = getViewRefNameFromText(e.getText(), variableName);
                            String viewId = StringUtil.formatCamelToUnderline(viewRef);
                            String viewType = getViewTypeFromXml(viewBindingType.getPresentableText(), viewId);

                            if (viewType != null) {
                                toReplace = variableName + "." + viewRef;
                                if (!viewRef.endsWith("View")) {
                                    viewRef += "View";
                                }
                                replace = viewRef;
                                // create [TextView textView = binding.findViewById(R.id.text);]
                                if (!currentFDVBIs.containsKey(replace)) {
                                    String findViewByIdStatement = viewType + " " + replace + " = " + variableName + ".findViewById(R.id." + viewId + ");";
                                    currentFDVBIs.put(replace, findViewByIdStatement);

                                    addImport(ViewUtil.getViewClassPath(viewType));
                                }
                            }
                        }
                        if (!replace.equals(toReplace)) {
                            String newStatement = e.getText().replaceAll(toReplace, replace);
                            e.replace(mElementFactory.createStatementFromText(newStatement, null));
                        }
                    }
                }
                PsiElement completelyStatement = localVariable;
                while (!currentFDVBIs.isEmpty() && completelyStatement != null) {
                    if (completelyStatement instanceof PsiStatement && completelyStatement.getText().endsWith(";")) {
                        for (String findViewByIdStatement : currentFDVBIs.values()) {
                            mClass.addAfter(mElementFactory.createStatementFromText(findViewByIdStatement, null), completelyStatement);
                        }
                        break;
                    }
                    completelyStatement = completelyStatement.getParent();
                }

                break;
            }
            parent = parent.getParent();
        }
    }

    /**
     * get xxx from *binding.xxx*
     */
    private String getViewRefNameFromText(String text, String binding) {
        int start = text.indexOf(binding + ".") + binding.length() + 1;
        if (start == binding.length()) {
            return null;
        }
        int end = start;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z') {
                continue;
            }
            end = i;
            break;
        }
        return text.substring(start, end);
    }

    /**
     * mViewBinding.xxx -> mXxxView
     * mViewBinding.getRoot -> mViewBinding
     */
    private void replaceAllViewRefsFromFieldBinding(PsiType viewBindingType, PsiField viewField, PsiElement
            assignmentElement) {
        Collection<PsiReference> references = ReferencesSearch.search(viewField, GlobalSearchScope.projectScope(mClass.getProject())).findAll();
        for (PsiReference r : references) {
            PsiElement parent = r.getElement().getParent();
            if (parent instanceof PsiReferenceExpression) {
                PsiElement integrity = parent.getParent();
                if (integrity.getText().endsWith(".getRoot()")) {
                    String newText = r.getElement().getText();
                    if (newText.contains("this.")) {
                        newText = newText.replaceAll("this.", "");
                    }
                    integrity.replace(mElementFactory.createReferenceFromText(newText, null));
                } else if (integrity.getText().matches(".*.set.*\\)$")
                        || integrity.getText().matches(".*.get.*\\)$")
                        || integrity.getText().contains("()")) {
//                        integrity.delete();
                } else if (!integrity.getText().contains("\n")) {
                    String viewKey = getRefName(parent);
                    if (!mViewFields.containsKey(viewKey)) {
                        createField(viewBindingType, viewKey, viewField);
                        createFindViewByIdStatement(assignmentElement, ((PsiReferenceExpression) parent.getReference()).getQualifier().getText(), viewKey);
                    }
                    if (mViewFields.get(viewKey) != null) {
                        parent.replace(mElementFactory.createReferenceFromText(mViewFields.get(viewKey), null));
                    }
                }
            } else if (parent instanceof PsiBinaryExpression) {

            }
        }
    }

    private void createFindViewByIdStatement(PsiElement assignmentElement, String viewRootText, String viewKey) {
        PsiElement completelyStatement = assignmentElement;
        while (completelyStatement != null) {
            if (completelyStatement instanceof PsiStatement && completelyStatement.getText().endsWith(";")) {
                String findViewByIdStatement = mViewFields.get(viewKey) + " = " + viewRootText + ".findViewById(R.id." + StringUtil.formatCamelToUnderline(viewKey) + ");";
                mClass.addAfter(mElementFactory.createStatementFromText(findViewByIdStatement, null), completelyStatement);
                break;
            }
            completelyStatement = completelyStatement.getParent();
        }
    }

    private String getRefName(PsiElement originBindingRef) {
        PsiElement[] children = originBindingRef.getChildren();
        for (PsiElement child : children) {
            if (child instanceof PsiIdentifier) {
                return child.getText();
            }
        }
        return null;
    }

    private void createField(PsiType viewBindingClass, String text, PsiField viewField) {
        String newField;
        char firstChar = text.charAt(0);
        newField = "m" + text.replaceFirst(String.valueOf(firstChar), StringUtils.upperCase(String.valueOf(firstChar)));
        if (!newField.endsWith("View")) {
            newField += "View";
        }
        if (isFieldExist(newField)) {
            newField += "2";
        }
        mViewFields.put(text, newField);

        PsiModifierList modifierList = viewField.getModifierList();
        String modifier = modifierList == null || TextUtils.isEmpty(modifierList.getText()) ? "" : modifierList.getText() + " ";
        String type = getViewTypeFromXml(viewBindingClass.getPresentableText(), StringUtil.formatCamelToUnderline(text));
        if (type == null) return;

        String simpleType = type;

        if (type.contains(".")) {
            simpleType = type.substring(type.lastIndexOf(".") + 1);
        }
        PsiField fieldFromText = mElementFactory.createFieldFromText(modifier + simpleType + " " + newField + ";", mClass);
        mClass.add(fieldFromText);
        addImport(ViewUtil.getViewClassPath(type));
    }

    @Nullable
    private String getViewTypeFromXml(String viewBindingType, String id) {
        int bindingIndex = viewBindingType.lastIndexOf("Binding");
        if (bindingIndex == -1) {
            throw new IllegalStateException("Error happened when handle " + mClass.getName() + ",maybe you have initial the layout more than once!");
        }
        if (!mBindingViewIdsMap.containsKey(viewBindingType)) {
            String bindingLayoutName = viewBindingType.substring(0, bindingIndex);
            String xmlName = StringUtil.formatCamelToUnderline(bindingLayoutName).concat(".xml");
            PsiFile[] psiFiles = FilenameIndex.getFilesByName(mClass.getProject(), xmlName, GlobalSearchScope.allScope(mClass.getProject()));
            if (psiFiles.length == 0) {
                throw new IllegalStateException("Error happened when parse view id from layout " + xmlName + ",please rename the layout from xxx02 to xxx_02!");
            }
            PsiFile xmlFile = psiFiles[0];
            mBindingViewIdsMap.put(viewBindingType, getIdsFromLayoutXml(xmlFile));
        }
        return mBindingViewIdsMap.get(viewBindingType).get(id);
    }

    private Map<String, String> getIdsFromLayoutXml(PsiElement xmlFile) {
        HashMap<String, String> map = new HashMap<>();

        for (PsiElement element : xmlFile.getChildren()) {
            if (element instanceof XmlDocument) {
                for (PsiElement tag : element.getChildren()) {
                    getIdsFromAttrs(tag, map);
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
        PsiClass clazz = JavaPsiFacade.getInstance(mClass.getProject()).findClass(packagePath, GlobalSearchScope.allScope(mClass.getProject()));
        if (clazz != null)
            importList.add(mElementFactory.createImportStatement(clazz));
    }

}
