package me.texy.databindinghunter;

import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import me.texy.databindinghunter.util.StringUtil;

import java.util.HashSet;
import java.util.Set;

public class JavaBindingHunter {

    private static final String CLASS_PATH_DATA_BINDING_UTIL = "android.databinding.DataBindingUtil";
    private static final String CLASS_NAME_DATA_BINDING_UTIL = "DataBindingUtil";
    private static final String CLASS_PATH_VIEW_DATA_BINDING = "android.databinding.ViewDataBinding";
    private PsiClass mClass;
    private PsiElementFactory mElementFactory;
    private Set<String> mDataBindingImports = new HashSet<>();

    public JavaBindingHunter(PsiClass psiClass) {
        this.mClass = psiClass;
        mElementFactory = JavaPsiFacade.getElementFactory(mClass.getProject());
    }

    public void hunt() {
        if (mClass == null) return;

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
                    replaceViewBindingSubClass(dataBindingCall, classPath);
                    break;
                }
            }
        }

        PsiElement[] elements = statement.getChildren();
        for (PsiElement element : elements) {
            replaceDataBindingElementRecursive(element);
        }
    }

    private void replaceViewBindingSubClass(PsiMethodCallExpression dataBindingCall, String subclass) {
        // 1) replace 「FooBinding binding」 with 「View binding」
        replaceDeclareTypeWithView(dataBindingCall.getParent());

        // 2) replace DataBindingUtil/FooBinding.bind/inflate with inflater.inflate
        replaceSubClassBindingMethodCall(dataBindingCall, subclass);

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
            replaceBindingIdViews();
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

    private void replaceSubClassBindingMethodCall(PsiMethodCallExpression dataBindingCall, String subclass) {
        String plainText = dataBindingCall.getText();

        String toReplace = StringUtil.getClassNameFromPath(subclass);
        if (plainText.contains(subclass)) {
            toReplace = subclass;
        }
        if (plainText.contains(toReplace + ".bind")) {
            PsiExpression[] expressions = dataBindingCall.getArgumentList().getExpressions();
            if (expressions.length != 1) {
                System.out.print("bind method's parameters not invalid,only support bind(View view)");
                return;
            }
            dataBindingCall.replace(mElementFactory.createExpressionFromText(expressions[0].getText(), null));
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
                    System.out.print("bind method's parameters not invalid,only support:" +
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
            dataBindingCall.replace(mElementFactory.createExpressionFromText(inflateText.toString(), null));
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
