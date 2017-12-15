package com.leeiidesu.translate;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.google.common.base.CaseFormat;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.LangDataKeys;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.progress.util.ProgressWindow;
import com.intellij.openapi.progress.util.ProgressWindowWithNotification;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by dgg on 2017/6/22.
 */
public class TranslateAction extends AnAction {
    private static final Logger log = Logger.getInstance(TranslateAction.class);

    public static boolean isContainChinese(String str) {

        Pattern p = Pattern.compile("[\u4e00-\u9fa5]");
        Matcher m = p.matcher(str);
        if (m.find()) {
            return true;
        }
        return false;
    }

    @Override
    public void actionPerformed(AnActionEvent e) {
        Project project = e.getProject();
        Editor editor = e.getData(PlatformDataKeys.EDITOR);

        if (editor == null) return;

        SelectionModel selectionModel = editor.getSelectionModel();
        String selectedText = selectionModel.getSelectedText();
        int start = selectionModel.getSelectionStart();
        int end = selectionModel.getSelectionEnd();


        System.out.println(selectedText);
        if (selectedText == null) {
            Utils.showErrorNotification(project, "当前未选择任何文字");
        } else {
            PsiFile data = e.getData(LangDataKeys.PSI_FILE);

            if (!findStringXmlIsHasThisValueAndReplace(data, start, end, selectedText)) {

                //如果包含中文 先翻译再加入
                if (isContainChinese(selectedText)) {

                    ProgressWindow progressWindow = new ProgressWindowWithNotification(false, project);
                    ProgressManager.getInstance()
                            .runProcessWithProgressAsynchronously(new Task.Backgroundable(project, "正在翻译...") {
                                @Override
                                public void run(@NotNull ProgressIndicator progressIndicator) {
                                    String url = Utils.getUrl(selectedText);
                                    progressWindow.setTitle("正在翻译...");
                                    progressWindow.setText("正在翻译 " + selectedText);
                                    progressWindow.setText2(url);
                                    progressWindow.setIndeterminate(true);

                                    OkHttpClient client = new OkHttpClient.Builder()
                                            .connectTimeout(5000, TimeUnit.MILLISECONDS)
                                            .readTimeout(5000, TimeUnit.MILLISECONDS)
                                            .build();

                                    Request build = new Request.Builder()
                                            .url(url)
                                            .get()
                                            .build();

                                    Call call = client.newCall(build);


                                    try {
                                        Response response = call.execute();
                                        String string = response.body().string();

                                        JSONObject object = JSON.parseObject(string);

                                        int errorCode = object.getIntValue("errorCode");
                                        if (errorCode == 0) {
                                            //翻译结果
                                            JSONArray translation = object.getJSONArray("translation");

                                            System.out.println(translation.toString());

                                            if (translation.size() > 0) {
                                                String text = translation.getString(0);
                                                //XmlElement

                                                log.info("translate_result = " + text);

                                                System.out.println(text);

                                                replaceThisAndInsertToString(text, data, start, end, selectedText);
                                                Utils.showInfoNotification(project, "翻译成功");
                                            } else {
                                                Utils.showErrorNotification(project, "翻译失败，请手动操作或者重试");
                                            }
                                        } else {
                                            Utils.showErrorNotification(project, "翻译失败，请手动操作或者重试");
                                        }
                                    } catch (IOException e1) {
                                        e1.printStackTrace();
                                        Utils.showErrorNotification(project, "翻译失败，请手动操作或者重试");
                                    }
                                }
                            }, progressWindow);
                } else {
                    //全英文直接加入String

                    replaceThisAndInsertToString(selectedText, data, start, end, selectedText);
                }
            }
        }
    }

    private void replaceThisAndInsertToString(String text, PsiFile file, int start, int end, String selectedText) {
        new WriteCommandAction.Simple(file.getProject()) {
            @Override
            protected void run() throws Throwable {
                Document document = PsiDocumentManager.getInstance(file.getProject())
                        .getDocument(file);

                String toText = CaseFormat.UPPER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, text.replace(' ', '_').replaceAll("[^_0-9a-zA-Z]", "").toLowerCase());

                if (toText.length() > 32) toText = toText.substring(0, 32);


                XmlFile stringResXml = getStringResXml(file.getParent());

                XmlTag rootTag = stringResXml.getRootTag();

                String name = addTagIfNotHas(file.getProject(), rootTag, toText, selectedText, 0);

                StringBuilder builder = new StringBuilder(document.getText());
                document.setText(
                        file instanceof PsiJavaFile ?
                                builder.replace(start - 1, end + 1, "R.string." + name)
                                        .toString() :
                                builder.replace(start, end, "@string/" + name)
                                        .toString()
                );
            }
        }.execute();
    }

    private String addTagIfNotHas(Project project, XmlTag rootTag, String toText, String selectedText, int count) {

        String name = count != 0 ? String.format(Locale.CHINA, "%s_%d", toText, count) : toText;

        if (!hasTagName(rootTag, "string", "name", name)) {
            XmlTag tag = XmlElementFactory.getInstance(project)
                    .createTagFromText(String.format(Locale.CHINA, "<string name=\"%s\">%s</string>", name, selectedText));
            rootTag.addSubTag(tag, false);
            return name;
        } else {
            return addTagIfNotHas(project, rootTag, toText, selectedText, ++count);
        }
    }

    private boolean hasTagName(XmlTag rootTag, String typeName, String attrType, @NotNull String attrName) {
        XmlTag[] subTags = rootTag.findSubTags(typeName);
        if (subTags.length == 0) return false;
        for (XmlTag tag : subTags) {
            if (attrName.equals(tag.getAttributeValue(attrType))) {
                return true;
            }
        }
        return false;
    }

    private boolean findStringXmlIsHasThisValueAndReplace(PsiFile file, int start, int end, String selectedText) {
        XmlFile stringResXml = getStringResXml(file.getParent());
        if (stringResXml == null) return false;
        else {
            XmlTag rootTag = stringResXml.getRootTag();

            XmlTag[] strings = rootTag.findSubTags("string");
            for (XmlTag tag : strings) {
                if (tag.getValue().getText().equals(selectedText)) {
                    String name = tag.getAttributeValue("name");
                    if (file instanceof PsiJavaFile) {
                        Document document = PsiDocumentManager.getInstance(file.getProject())
                                .getDocument(file);

                        new WriteCommandAction.Simple(file.getProject()) {
                            @Override
                            protected void run() throws Throwable {
                                StringBuilder builder = new StringBuilder(document.getText());
                                document.setText(
                                        builder.replace(start - 1, end + 1, "R.string." + name)
                                                .toString()
                                );
                            }
                        }.execute();
                        return true;
                    } else if (file instanceof XmlFile) {
                        Document document = PsiDocumentManager.getInstance(file.getProject())
                                .getDocument(file);

                        new WriteCommandAction.Simple(file.getProject()) {
                            @Override
                            protected void run() throws Throwable {
                                StringBuilder builder = new StringBuilder(document.getText());
                                document.setText(
                                        builder.replace(start, end, "@string/" + name)
                                                .toString()
                                );
                            }
                        }.execute();
                        return true;
                    }
                    return false;
                }
            }
        }
        return false;
    }

    private XmlFile getStringResXml(PsiDirectory directory) {
        if (directory == null) return null;
        PsiFile file = directory.findFile("strings.xml");
        PsiDirectory res = directory.findSubdirectory("res");
        if (file != null) return (XmlFile) directory.findFile("strings.xml");
        else if (res != null) return getStringResXml(res.findSubdirectory("values"));
        else {
            return getStringResXml(directory.getParent());
        }
    }


}
