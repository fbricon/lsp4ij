/*******************************************************************************
 * Copyright (c) 2020 Red Hat, Inc.
 * Distributed under license by Red Hat, Inc. All rights reserved.
 * This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors:
 * Red Hat, Inc. - initial API and implementation
 ******************************************************************************/
package com.redhat.devtools.lsp4ij.features.completion;

import com.intellij.codeInsight.AutoPopupController;
import com.intellij.codeInsight.completion.CodeCompletionHandlerBase;
import com.intellij.codeInsight.completion.CompletionInitializationContext;
import com.intellij.codeInsight.completion.InsertionContext;
import com.intellij.codeInsight.completion.LookupElementListPresenter;
import com.intellij.codeInsight.lookup.*;
import com.intellij.codeInsight.template.Template;
import com.intellij.codeInsight.template.TemplateManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorModificationUtil;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiFile;
import com.redhat.devtools.lsp4ij.LSPIJUtils;
import com.redhat.devtools.lsp4ij.LanguageServerItem;
import com.redhat.devtools.lsp4ij.commands.CommandExecutor;
import com.redhat.devtools.lsp4ij.commands.LSPCommandContext;
import com.redhat.devtools.lsp4ij.features.completion.snippet.LspSnippetIndentOptions;
import com.redhat.devtools.lsp4ij.internal.StringUtils;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static com.redhat.devtools.lsp4ij.features.completion.snippet.LspSnippetVariableConstants.*;
import static com.redhat.devtools.lsp4ij.internal.CompletableFutures.isDoneNormally;
import static com.redhat.devtools.lsp4ij.internal.CompletableFutures.waitUntilDone;
import static com.redhat.devtools.lsp4ij.ui.IconMapper.getIcon;

/**
 * LSP completion lookup element.
 */
public class LSPCompletionProposal extends LookupElement {
    private static final Logger LOGGER = LoggerFactory.getLogger(LSPCompletionProposal.class);

    private final CompletionItem item;
    private final PsiFile file;
    private final Boolean supportResolveCompletion;
    private final boolean supportSignatureHelp;
    private final LSPCompletionContributor completionContributor;

    // offset where completion has been triggered
    // ex : string.charA|
    private final int completionOffset;

    // offset where prefix completion starts
    // ex : string.|charA
    private int prefixStartOffset;

    private final Editor editor;
    private final LanguageServerItem languageServer;
    private CompletableFuture<CompletionItem> resolvedCompletionItemFuture;

    public LSPCompletionProposal(@NotNull PsiFile file,
                                 @NotNull Editor editor,
                                 int completionOffset,
                                 @NotNull CompletionItem item,
                                 @NotNull LanguageServerItem languageServer,
                                 @NotNull LSPCompletionContributor completionContributor) {
        this.file = file;
        this.item = item;
        this.editor = editor;
        this.languageServer = languageServer;
        this.completionContributor = completionContributor;
        this.completionOffset = completionOffset;
        this.prefixStartOffset = getPrefixStartOffset(editor.getDocument(), completionOffset);
        this.supportResolveCompletion = languageServer.isResolveCompletionSupported();
        this.supportSignatureHelp = languageServer.isSignatureHelpSupported();
        putUserData(CodeCompletionHandlerBase.DIRECT_INSERTION, true);
    }

    @Override
    public void handleInsert(@NotNull InsertionContext context) {
        Template template = null;
        if (item.getInsertTextFormat() == InsertTextFormat.Snippet) {
            // Insert text has snippet syntax, ex : ${1:name}
            String snippetContent = getInsertText();
            // Get the indentation settings
            LspSnippetIndentOptions indentOptions = CompletionProposalTools.createLspIndentOptions(snippetContent, file);
            // Load the insert text to build:
            // - an IJ Template instance which will take care of replacement of placeholders
            // - the insert text without placeholders
            template = SnippetTemplateFactory.createTemplate(snippetContent, context.getProject(), name -> getVariableValue(name), indentOptions);
            // Update the TextEdit with the content snippet content without placeholders
            // ex : ${1:name} --> name
            updateInsertTextForTemplateProcessing(template.getTemplateText());
        }

        // Apply all text edits
        apply(context.getDocument(), context.getCompletionChar(), 0, context.getOffset(CompletionInitializationContext.SELECTION_END_OFFSET));

        if (shouldStartTemplate(template)) {
            // LSP completion with snippet syntax, activate the inline template
            context.setAddCompletionChar(false);
            EditorModificationUtil.moveCaretRelatively(editor, -template.getTemplateText().length());
            TemplateManager.getInstance(context.getProject()).startTemplate(context.getEditor(), template);
        }

        // Execute custom command of the completion item if needed
        Command command = item.getCommand();
        if (command != null) {
            executeCustomCommand(command, context.getFile(), context.getEditor(), languageServer);
        }

        if (supportSignatureHelp) {
            // The language server supports signature help, open the parameter info popup
            AutoPopupController popupController = AutoPopupController.getInstance(context.getProject());
            if (popupController != null) {
                popupController.autoPopupParameterInfo(editor, null);
            }
        }
    }

    /**
     * Returns true if the given template must be executed and false otherwise.
     *
     * @param template the template.
     * @return true if the given template must be executed and false otherwise.
     */
    private static boolean shouldStartTemplate(@Nullable Template template) {
        return template != null // Completion item is a Snippet (InsertTextFormat.Snippet)
                && (template.getSegmentsCount() > 0 // There are some tabstops, e.g. $0, $1
                || !template.getVariables().isEmpty()); // There are some placeholders, e.g ${1:name}
    }


    /**
     * Update the insert text with the given new value <code>newText</code>.
     *
     * <p>
     * This method is called when a completion insert text uses snippet syntax (ex : ${0:var}) and
     * the insert text must remove this snippet syntax (ex : var) to process after the IntelliJ template.
     * </p>
     *
     * @param newText the new text (without snippet syntax)
     */
    private void updateInsertTextForTemplateProcessing(String newText) {
        Either<TextEdit, InsertReplaceEdit> textEdit = this.item.getTextEdit();
        if (textEdit != null) {
            if (textEdit.isLeft()) {
                textEdit.getLeft().setNewText(newText);
            } else {
                textEdit.getRight().setNewText(newText);
            }
        } else {
            if (item.getInsertText() != null) {
                item.setInsertText(newText);
            }
        }
    }

    /**
     * Returns the text content to insert coming from the LSP CompletionItem.
     *
     * @return the text content to insert coming from the LSP CompletionItem.
     */
    private String getInsertText() {
        Either<TextEdit, InsertReplaceEdit> textEdit = this.item.getTextEdit();
        if (textEdit != null) {
            if (textEdit.isLeft()) {
                return textEdit.getLeft().getNewText();
            } else {
                return textEdit.getRight().getNewText();
            }
        }

        String insertText = this.item.getInsertText();
        if (insertText != null) {
            return insertText;
        }
        return this.item.getLabel();
    }

    @Override
    public Set<String> getAllLookupStrings() {
        if (StringUtils.isBlank(item.getFilterText())) {
            return super.getAllLookupStrings();
        }
        return new HashSet<>(Arrays.asList(item.getFilterText(), item.getLabel()));
    }

    @NotNull
    @Override
    public String getLookupString() {
        return item.getLabel();
    }

    private boolean isDeprecated() {
        return (item.getTags() != null && item.getTags().contains(CompletionItemTag.Deprecated))
                || (item.getDeprecated() != null && item.getDeprecated().booleanValue());
    }

    @Override
    public void renderElement(LookupElementPresentation presentation) {
        presentation.setItemText(item.getLabel());
        presentation.setTypeText(item.getDetail());
        presentation.setIcon(getIcon(item));
        if (isDeprecated()) {
            presentation.setStrikeout(true);
        }
    }

    @Override
    public @Nullable LookupElementRenderer<? extends LookupElement> getExpensiveRenderer() {
        if (!isSelectedCompletionItem()) {
            // The lookup item is not selected ignore it
            return null;
        }
        // Here the IJ lookup item is selected.
        if (needToResolveCompletionDetail()) {
            // The LSP completion item 'detail' is not filled, try to resolve it
            // inside getExpensiveRenderer() which should not impact performance.
            CompletionItem resolved = getResolvedCompletionItem();
            if (resolved != null && resolved.getDetail() != null) {
                item.setDetail(resolved.getDetail());

                return new LookupElementRenderer<LookupElement>() {
                    @Override
                    public void renderElement(LookupElement element, LookupElementPresentation presentation) {
                        LSPCompletionProposal.this.renderElement(presentation);
                    }
                };
            }
        }
        if (item.getDetail() == null) {
            // The detail cannot be resolved, set empty string to avoid trying to resolve again the completion item
            item.setDetail("");
        }
        return null;
    }

    /**
     * Returns true if the LSP completion item 'detail' must be resolved and false otherwise.
     *
     * @return true if the LSP completion item 'detail' must be resolved and false otherwise.
     */
    public boolean needToResolveCompletionDetail() {
        return item.getDetail() == null && supportResolveCompletion;
    }

    protected void apply(Document document, char trigger, int stateMask, int offset) {
        String insertText = null;
        Either<TextEdit, InsertReplaceEdit> eitherTextEdit = item.getTextEdit();
        TextEdit textEdit = null;
        if (eitherTextEdit != null) {
            if (eitherTextEdit.isLeft()) {
                textEdit = eitherTextEdit.getLeft();
            } else {
                // trick to partially support the new InsertReplaceEdit from LSP 3.16. Reuse previously code for TextEdit.
                InsertReplaceEdit insertReplaceEdit = eitherTextEdit.getRight();
                textEdit = new TextEdit(insertReplaceEdit.getInsert(), insertReplaceEdit.getNewText());
            }
        }
        try {
            if (textEdit == null) {
                // ex:
                // {
                //    "label": "let",
                //    "kind": 15,
                //    "detail": "Insert let",
                //    "insertText": "(let [${1:binding} ${2:value}])",
                //    "insertTextFormat": 2

                insertText = getInsertText();
                int startOffset = this.prefixStartOffset;
                int endOffset = offset;
                // Try to get the text range to replace it.
                // foo.b|ar --> foo.[bar]
                Position start = LSPIJUtils.toPosition(startOffset, document);
                Position end = LSPIJUtils.toPosition(endOffset, document); // need 2 distinct objects
                textEdit = new TextEdit(new Range(start, end), insertText);
            } else if (offset > this.completionOffset) {
                // characters were added after completion was activated
                int shift = offset - this.completionOffset;
                textEdit.getRange().getEnd().setCharacter(textEdit.getRange().getEnd().getCharacter() + shift);
            }
            { // workaround https://github.com/Microsoft/vscode/issues/17036
                Position start = textEdit.getRange().getStart();
                Position end = textEdit.getRange().getEnd();
                if (start.getLine() > end.getLine() || (start.getLine() == end.getLine() && start.getCharacter() > end.getCharacter())) {
                    textEdit.getRange().setEnd(start);
                    textEdit.getRange().setStart(end);
                }
            }
            { // allow completion items to be wrong with a too wide range
                Position documentEnd = LSPIJUtils.toPosition(document.getTextLength(), document);
                Position textEditEnd = textEdit.getRange().getEnd();
                if (documentEnd.getLine() < textEditEnd.getLine()
                        || (documentEnd.getLine() == textEditEnd.getLine() && documentEnd.getCharacter() < textEditEnd.getCharacter())) {
                    textEdit.getRange().setEnd(documentEnd);
                }
            }

            if (insertText != null) {
                // try to reuse existing characters after completion location
                int shift = offset - this.prefixStartOffset;
                int commonSize = 0;
                while (commonSize < insertText.length() - shift
                        && document.getTextLength() > offset + commonSize
                        && document.getText().charAt(this.prefixStartOffset + shift + commonSize) == insertText.charAt(commonSize + shift)) {
                    commonSize++;
                }
                textEdit.getRange().getEnd().setCharacter(textEdit.getRange().getEnd().getCharacter() + commonSize);
            }

            List<TextEdit> additionalEdits = item.getAdditionalTextEdits();
            if (additionalEdits == null && supportResolveCompletion) {
                // The LSP completion item 'additionalEdits' is not filled, try to resolve it.
                CompletionItem resolved = getResolvedCompletionItem();
                if (resolved != null) {
                    additionalEdits = resolved.getAdditionalTextEdits();
                }
            }
            if (additionalEdits != null && !additionalEdits.isEmpty()) {
                List<TextEdit> allEdits = new ArrayList<>();
                allEdits.add(textEdit);
                allEdits.addAll(additionalEdits);
                LSPIJUtils.applyEdits(editor, document, allEdits);
            } else {
                LSPIJUtils.applyEdits(editor, document, Collections.singletonList(textEdit));
            }


        } catch (RuntimeException ex) {
            LOGGER.warn(ex.getLocalizedMessage(), ex);
        }
    }


    /**
     * Execute custom command of the completion item.
     *
     * @param command        the command.
     * @param file           the Psi file.
     * @param editor         the editor.
     * @param languageServer the language server.
     */
    private static void executeCustomCommand(@NotNull Command command,
                                             PsiFile file,
                                             Editor editor,
                                             LanguageServerItem languageServer) {
        // Execute custom command of the completion item.
        CommandExecutor.executeCommand(new LSPCommandContext(command, file, editor, languageServer));
    }

    public @Nullable Range getTextEditRange() {
        Either<TextEdit, InsertReplaceEdit> textEdit = item.getTextEdit();
        if (textEdit == null) {
            return null;
        }
        if (item.getTextEdit().isLeft()) {
            return item.getTextEdit().getLeft().getRange();
        } else {
            // here providing insert range, currently do not know if insert or replace is requested
            return item.getTextEdit().getRight().getInsert();
        }
    }

    public CompletionItem getItem() {
        return item;
    }

    /**
     * Return the result of the resolved LSP variable and null otherwise.
     *
     * @param variableName the variable name to resolve.
     * @return the result of the resolved LSP variable and null otherwise.
     */
    private @Nullable String getVariableValue(String variableName) {
        Document document = editor.getDocument();
        switch (variableName) {
            case TM_FILENAME_BASE:
                String fileName = LSPIJUtils.getFile(document).getNameWithoutExtension();
                return fileName != null ? fileName : ""; //$NON-NLS-1$
            case TM_FILENAME:
                return LSPIJUtils.getFile(document).getName();
            case TM_FILEPATH:
                return LSPIJUtils.getFile(document).getPath();
            case TM_DIRECTORY:
                return LSPIJUtils.getFile(document).getParent().getPath();
            case TM_LINE_INDEX:
                int lineIndex = getTextEditRange().getStart().getLine();
                return Integer.toString(lineIndex);
            case TM_LINE_NUMBER:
                int lineNumber = getTextEditRange().getStart().getLine();
                return Integer.toString(lineNumber + 1);
            case TM_CURRENT_LINE:
                int currentLineIndex = getTextEditRange().getStart().getLine();
                try {
                    int lineOffsetStart = document.getLineStartOffset(currentLineIndex);
                    int lineOffsetEnd = document.getLineEndOffset(currentLineIndex);
                    String line = document.getText(new TextRange(lineOffsetStart, lineOffsetEnd));
                    return line;
                } catch (RuntimeException e) {
                    LOGGER.warn(e.getMessage(), e);
                    return ""; //$NON-NLS-1$
                }
            case TM_SELECTED_TEXT:
                Range selectedRange = getTextEditRange();
                try {
                    int startOffset = LSPIJUtils.toOffset(selectedRange.getStart(), document);
                    int endOffset = LSPIJUtils.toOffset(selectedRange.getEnd(), document);
                    String selectedText = document.getText(new TextRange(startOffset, endOffset));
                    return selectedText;
                } catch (RuntimeException e) {
                    LOGGER.warn(e.getMessage(), e);
                    return ""; //$NON-NLS-1$
                }
            case TM_CURRENT_WORD:
                return ""; //$NON-NLS-1$
            default:
                return null;
        }
    }

    public MarkupContent getDocumentation() {
        if (item.getDocumentation() == null && supportResolveCompletion) {
            // The LSP completion item 'documentation' is not filled, try to resolve it
            // As documentation is computed in a Thread it should not impact performance.
            CompletionItem resolved = getResolvedCompletionItem();
            if (resolved != null) {
                item.setDocumentation(resolved.getDocumentation());
            }
        }
        return getDocumentation(item.getDocumentation());
    }

    private static MarkupContent getDocumentation(Either<String, MarkupContent> documentation) {
        if (documentation == null) {
            return null;
        }
        if (documentation.isLeft()) {
            String content = documentation.getLeft();
            return new MarkupContent(MarkupKind.PLAINTEXT, content);
        }
        return documentation.getRight();
    }

    /**
     * Returns the resolved completion item and null otherwise.
     *
     * @return the resolved completion item and null otherwise.
     */
    private CompletionItem getResolvedCompletionItem() {
        if (resolvedCompletionItemFuture == null) {
            resolvedCompletionItemFuture = languageServer.getServer()
                    .getTextDocumentService()
                    .resolveCompletionItem(item);
        }
        try {
            // Wait until the future is finished and stop the wait if there are some ProcessCanceledException.
            waitUntilDone(resolvedCompletionItemFuture, file);
        } catch (ProcessCanceledException e) {//Since 2024.2 ProcessCanceledException extends CancellationException so we can't use multicatch to keep backward compatibility
            //TODO delete block when minimum required version is 2024.2
            return null;
        } catch (CancellationException e) {
            return null;
        } catch (ExecutionException e) {
            LOGGER.error("Error while consuming LSP 'completionItem/resolve' request", e);
            return null;
        }

        ProgressManager.checkCanceled();
        if (isDoneNormally(resolvedCompletionItemFuture)) {
            return resolvedCompletionItemFuture.getNow(null);
        }
        return null;
    }

    /**
     * Returns true if the LSP completion item is selected and false otherwise.
     *
     * @return true if the LSP completion item is selected and false otherwise.
     */
    private boolean isSelectedCompletionItem() {
        Lookup lookup = LookupManager.getActiveLookup(editor);
        if (lookup instanceof LookupElementListPresenter lookupElementListPresenter) {
            var selectedItem = lookupElementListPresenter.getCurrentItem() != null ? lookupElementListPresenter.getCurrentItem().getObject() : null;
            return this == selectedItem;
        }
        return false;
    }

    // --------------- Prefix start offset


    private int getPrefixStartOffset(@NotNull Document document, int completionOffset) {
        Either<TextEdit, InsertReplaceEdit> textEdit = this.item.getTextEdit();
        if (textEdit != null) {
            // case 1: text edit is defined,
            // return the range / insert start as prefix completion offset
            return getPrefixStartOffsetFromTextEdit(document, textEdit);
        }

        // case 2: text edit is undefined, try to compute the prefix start offset by using insertText
        String insertText = getInsertText();
        Integer prefixStartOffset = computePrefixStartFromInsertText(document, completionOffset, insertText);
        if (prefixStartOffset != null) {
            return prefixStartOffset;
        }
        return completionOffset;
    }


    /**
     * Returns the defined start prefix offset in the given text edit.
     *
     * @param document the document
     * @param textEdit the text edit.
     * @return
     */
    private static int getPrefixStartOffsetFromTextEdit(@NotNull Document document,
                                                        @NotNull Either<TextEdit, InsertReplaceEdit> textEdit) {
        if (textEdit.isLeft()) {
            return LSPIJUtils.toOffset(textEdit.getLeft().getRange().getStart(), document);
        }
        return LSPIJUtils.toOffset(textEdit.getRight().getInsert().getStart(), document);
    }

    @Nullable
    private Integer computePrefixStartFromInsertText(@NotNull Document document,
                                                     int completionOffset,
                                                     String insertText) {
        // case 2.1: first strategy, we check if the left content of the completion offset
        // matches the full insertText left content
        // ex :
        // insertText= 'foo.bar'
        // document= {foo.b|}
        // we have to return {| as prefix start offset

        Integer prefixStartOffset = getPrefixStartOffsetWhichMatchesLeftContent(document, completionOffset, insertText);
        if (prefixStartOffset != null) {
            return prefixStartOffset;
        }

        // case 2.2: second strategy, we collect word range at
        // ex :
        // insertText= '(let [${1:binding} ${2:value}])'
        // document= le
        // we have to return |le as prefix start offset

        TextRange wordRange = LSPIJUtils.getWordRangeAt(document, completionOffset);
        if (wordRange != null) {
            return wordRange.getStartOffset();
        }
        return null;
    }

    @Nullable
    private static Integer getPrefixStartOffsetWhichMatchesLeftContent(@NotNull Document document,
                                                                       int completionOffset,
                                                                       @NotNull String insertText) {
        int startOffset = Math.max(0, completionOffset - insertText.length());
        int endOffset = startOffset + Math.min(insertText.length(), completionOffset);
        String subDoc = document.getText(new TextRange(startOffset, endOffset)); // "".ch
        for (int i = 0; i < insertText.length() && i < completionOffset; i++) {
            String tentativeCommonString = subDoc.substring(i);
            if (insertText.startsWith(tentativeCommonString)) {
                return completionOffset - tentativeCommonString.length();
            }
        }
        return null;
    }


}
