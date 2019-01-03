package me.texy.databindinghunter;

import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import me.texy.databindinghunter.util.StringUtil;
import me.texy.databindinghunter.util.ViewUtil;
import org.apache.commons.lang.StringUtils;
import org.apache.http.util.TextUtils;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

public class JavaBindingHunter {

    private static final String CLASS_PATH_DATA_BINDING_UTIL = "android.databinding.DataBindingUtil";
    private static final String CLASS_NAME_DATA_BINDING_UTIL = "DataBindingUtil";
    private static final String CLASS_PATH_VIEW_DATA_BINDING = "android.databinding.ViewDataBinding";

    // <DemoBinding,LayoutXmlInfo>
    private final HashMap<String, LayoutXmlInfo> mBindingXmlInfo;
    private PsiClass mClass;
    private PsiElementFactory mElementFactory;
    private Set<String> mDataBindingImports = new HashSet<>();
    private HashMap<String, String> mViewFields = new HashMap<>();

    public JavaBindingHunter(PsiClass psiClass, HashMap<String, LayoutXmlInfo> bindingLayouts) {
        this.mClass = psiClass;
        this.mBindingXmlInfo = bindingLayouts;
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

            replaceLocalBindingFromCodeBlock(viewBindingType, (PsiLocalVariable) dataBindingCallParent);
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
    private void replaceLocalBindingFromCodeBlock(PsiType viewBindingType, PsiLocalVariable localVariable) {
        PsiElement parent = localVariable;
        String variableName = localVariable.getName();

        while (parent != null) {
            if (parent instanceof PsiCodeBlock) {
                HashMap<String, String> currentFDVBIs = new HashMap<>();
                for (PsiStatement e : ((PsiCodeBlock) parent).getStatements()) {
                    replaceLocalBindingRefFromStatement(viewBindingType, localVariable, variableName, currentFDVBIs, e);
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

    private PsiStatement replaceLocalBindingRefFromStatement(PsiType viewBindingType, PsiLocalVariable localVariable, String variableName, HashMap<String, String> currentFDVBIs, PsiStatement e) {
        if (e.getText().contains(variableName)
                && !e.getText().contains(CLASS_NAME_DATA_BINDING_UTIL)
                && !e.getText().contains(localVariable.getType().getPresentableText())) {

            String replace = variableName;
            String toReplace = variableName;
            if (e.getText().contains(variableName + ".getRoot()")) {
                toReplace = variableName + ".getRoot".concat("()");
            } else if (e.getText().contains(variableName + ".")) {
                String viewRef = getViewRefNameFromText(e.getText(), variableName);
                String viewId = mBindingXmlInfo.get(viewBindingType.getPresentableText()).getIdByViewRefName(viewRef);
                String viewType = mBindingXmlInfo.get(viewBindingType.getPresentableText()).getViewTypeByViewRefName(viewRef);

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
                String newStatementText = e.getText().replace(toReplace, replace);
                // maybe one statement contains multi binding references,so we should replace recursive
                PsiStatement newStatement = replaceLocalBindingRefFromStatement(
                        viewBindingType, localVariable, variableName, currentFDVBIs, mElementFactory.createStatementFromText(newStatementText, null));
                e.replace(newStatement);
                return newStatement;
            }
        }
        return e;
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
            if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9') {
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
                    String viewRefName = getRefName(parent);
                    if (!mViewFields.containsKey(viewRefName)) {
                        createField(viewBindingType, viewRefName, viewField);
                        createFieldFindViewByIdStatement(assignmentElement, ((PsiReferenceExpression) parent.getReference()).getQualifier().getText(), viewRefName, viewBindingType);
                    }
                    if (mViewFields.get(viewRefName) != null) {
                        parent.replace(mElementFactory.createReferenceFromText(mViewFields.get(viewRefName), null));
                    }
                }
            } else if (parent instanceof PsiBinaryExpression) {

            }
        }
    }

    private void createFieldFindViewByIdStatement(PsiElement assignmentElement, String viewRootText, String viewRefName, PsiType viewBindingType) {
        PsiElement completelyStatement = assignmentElement;
        while (completelyStatement != null) {
            if (completelyStatement instanceof PsiStatement && completelyStatement.getText().endsWith(";")) {
                String viewId = mBindingXmlInfo.get(viewBindingType.getPresentableText()).getIdByViewRefName(viewRefName);
                if (viewId != null) {
                    String findViewByIdStatement = mViewFields.get(viewRefName) + " = " + viewRootText + ".findViewById(R.id." + viewId + ");";
                    mClass.addAfter(mElementFactory.createStatementFromText(findViewByIdStatement, null), completelyStatement);
                }
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

    private void createField(PsiType viewBindingClass, String viewRefName, PsiField bindingField) {
        String newField;
        char firstChar = viewRefName.charAt(0);
        newField = "m" + viewRefName.replaceFirst(String.valueOf(firstChar), StringUtils.upperCase(String.valueOf(firstChar)));
        if (!newField.endsWith("View")) {
            newField += "View";
        }
        if (isFieldExist(newField)) {
            newField += "2";
        }
        mViewFields.put(viewRefName, newField);

        PsiModifierList modifierList = bindingField.getModifierList();
        String modifier = modifierList == null || TextUtils.isEmpty(modifierList.getText()) ? "" : modifierList.getText() + " ";
        String type = mBindingXmlInfo.get(viewBindingClass.getPresentableText()).getViewTypeByViewRefName(viewRefName);
        if (type == null) return;

        String simpleType = type;

        if (type.contains(".")) {
            simpleType = type.substring(type.lastIndexOf(".") + 1);
        }
        PsiField fieldFromText = mElementFactory.createFieldFromText(modifier + simpleType + " " + newField + ";", mClass);
        mClass.add(fieldFromText);
        addImport(ViewUtil.getViewClassPath(type));
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
                String bindingLayoutName = StringUtil.getClassNameFromPath(toReplace);
                String layoutResource = "R.layout." + mBindingXmlInfo.get(bindingLayoutName).getXmlFile().getName().replaceAll(".xml", "");
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
