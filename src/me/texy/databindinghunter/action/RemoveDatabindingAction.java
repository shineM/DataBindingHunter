package me.texy.databindinghunter.action;

import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.ide.highlighter.XmlFileType;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.LangDataKeys;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.search.FileTypeIndex;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScopes;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.indexing.FileBasedIndex;
import me.texy.databindinghunter.JavaBindingHunter;
import me.texy.databindinghunter.LayoutXmlHunter;
import me.texy.databindinghunter.LayoutXmlInfo;
import me.texy.databindinghunter.util.StringUtil;
import org.jetbrains.annotations.NotNull;

import java.util.*;

public class RemoveDatabindingAction extends AnAction {

    @Override
    public void actionPerformed(AnActionEvent anActionEvent) {
        Project project = anActionEvent.getProject();
        if (project == null) {
            return;
        }
        VirtualFile[] files = project.getBaseDir().getChildren();
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Removing databinding...", false) {
            @Override
            public void run(@NotNull ProgressIndicator progressIndicator) {
                List<VirtualFile> mainDirs = new ArrayList<>();
                for (VirtualFile first : files) {
                    for (VirtualFile second : first.getChildren()) {
                        if (!second.getName().equals("src")) continue;
                        for (VirtualFile third : second.getChildren()) {
                            if (!third.getName().equals("main")) continue;
                            mainDirs.add(third);
                        }
                    }
                }
                WriteCommandAction.runWriteCommandAction(project, () -> {
                    StringBuilder stringBuilder = new StringBuilder();
                    try {
                        for (VirtualFile main : mainDirs) {
                            HashMap<String, LayoutXmlInfo> bindingLayouts = huntLayoutXml(progressIndicator, main, project);
                            progressIndicator.setFraction(0.5f);
                            huntJava(progressIndicator, main, project, stringBuilder, bindingLayouts);
                        }
                    } catch (Exception e) {
                        showErrorMsg(project, "actionPerformed error", e);
                    }
                    Messages.showInfoMessage(stringBuilder.toString(), "classes");
                });
                progressIndicator.cancel();
            }
        });

    }

    private void huntJava(@NotNull ProgressIndicator progressIndicator, VirtualFile third, Project project, StringBuilder stringBuilder, HashMap<String, LayoutXmlInfo> bindingLayouts) {
        Collection<VirtualFile> javaFiles = FileBasedIndex.getInstance().getContainingFiles(FileTypeIndex.NAME, JavaFileType.INSTANCE, GlobalSearchScopes.directoryScope(project, third, true));
        for (VirtualFile clazz : javaFiles) {
            progressIndicator.setText("processing " + clazz.getName());
            PsiClass psiClass = JavaPsiFacade.getInstance(project).findClass(
                    StringUtil.getClassPkgFromAbsDir(clazz.getPath()), GlobalSearchScopes.directoryScope(project, clazz.getParent(), false));

            if (psiClass != null) {
                try {
                    JavaBindingHunter javaBindingHunter = new JavaBindingHunter(psiClass, bindingLayouts);
                    if (javaBindingHunter.hunt()) {
                        stringBuilder.append(psiClass.getName()).append("\n");
                    }
                } catch (Exception e) {
                    showErrorMsg(project, psiClass.getName(), e);
                }
            }
        }
    }

    private void showErrorMsg(Project project, String fileName, Exception e) {
        StringBuilder message = new StringBuilder(e.getMessage() + "\n");
        for (int i = 0; i < Math.min(5, e.getStackTrace().length); i++) {
            message.append(e.getStackTrace()[i]).append("\n");
        }
        Messages.showErrorDialog(project, message.toString(), "Exception threw when handle " + fileName);
    }

    private HashMap<String, LayoutXmlInfo> huntLayoutXml(@NotNull ProgressIndicator progressIndicator, VirtualFile third, Project project) {
        Collection<VirtualFile> xmlFiles = FileBasedIndex.getInstance().getContainingFiles(FileTypeIndex.NAME, XmlFileType.INSTANCE, GlobalSearchScopes.directoryScope(project, third, true));
        HashMap<String, LayoutXmlInfo> bindingLayouts = new HashMap<>();
        for (VirtualFile xml : xmlFiles) {
            if (!xml.getParent().getPath().endsWith("layout")) continue;

            progressIndicator.setText("processing " + xml.getName());
            PsiFile[] filesByName = FilenameIndex.getFilesByName(project, xml.getName(), GlobalSearchScopes.directoryScope(project, xml.getParent(), false));
            try {
                LayoutXmlHunter xmlHunter = new LayoutXmlHunter(filesByName[0]);
                xmlHunter.hunt();
                String xmlName = filesByName[0].getName();
                // we can not calculate the camel to underline,such as Demo01 maybe demo_01 or demo_0_1,but we can do it reversely.
                bindingLayouts.put(StringUtil.formatUnderlineToCamel(xmlName.replaceAll(".xml", "")) + "Binding", new LayoutXmlInfo(filesByName[0]));
            } catch (Exception e) {
                showErrorMsg(project, xml.getName(), e);
            }
        }
        return bindingLayouts;
    }

    @Override
    public void update(AnActionEvent e) {
//        PsiClass psiClass = getPsiClassFromContext(e);
//        e.getPresentation().setEnabled(psiClass != null && !psiClass.isEnum() && !psiClass.isInterface());
    }

    private PsiClass getPsiClassFromContext(AnActionEvent e) {
        PsiFile psiFile = e.getData(LangDataKeys.PSI_FILE);
        Editor editor = e.getData(PlatformDataKeys.EDITOR);

        if (psiFile == null || editor == null) {
            return null;
        }

        int offset = editor.getCaretModel().getOffset();
        PsiElement element = psiFile.findElementAt(offset);

        return PsiTreeUtil.getParentOfType(element, PsiClass.class);
    }
}
