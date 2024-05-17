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

import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.completion.PrioritizedLookupElement;
import com.intellij.codeInsight.lookup.Lookup;
import com.intellij.codeInsight.lookup.LookupEvent;
import com.intellij.codeInsight.lookup.LookupListener;
import com.intellij.codeInsight.lookup.LookupManagerListener;
import com.intellij.codeInsight.lookup.impl.LookupImpl;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.redhat.devtools.lsp4ij.LSPFileSupport;
import com.redhat.devtools.lsp4ij.LSPIJUtils;
import com.redhat.devtools.lsp4ij.LanguageServerItem;
import com.redhat.devtools.lsp4ij.LanguageServersRegistry;
import com.redhat.devtools.lsp4ij.internal.StringUtils;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static com.redhat.devtools.lsp4ij.internal.CompletableFutures.isDoneNormally;
import static com.redhat.devtools.lsp4ij.internal.CompletableFutures.waitUntilDone;

/**
 * LSP completion contributor.
 */
public class LSPCompletionContributor extends CompletionContributor {
    private static final Logger LOGGER = LoggerFactory.getLogger(LSPCompletionContributor.class);

    @Override
    public void fillCompletionVariants(@NotNull CompletionParameters parameters, @NotNull CompletionResultSet result) {
        PsiFile psiFile = parameters.getOriginalFile();
        VirtualFile file = psiFile.getVirtualFile();
        if (file == null) {
            return;
        }

        if (!LanguageServersRegistry.getInstance().isFileSupported(psiFile)) {
            return;
        }

        Editor editor = parameters.getEditor();
        Document document = editor.getDocument();
        int offset = parameters.getOffset();

        // Get LSP completion items from cache or create them
        LSPCompletionParams params = new LSPCompletionParams(LSPIJUtils.toTextDocumentIdentifier(file), LSPIJUtils.toPosition(offset, document), offset);
        CompletableFuture<List<CompletionData>> future = LSPFileSupport.getSupport(psiFile)
                .getCompletionSupport()
                .getCompletions(params);
        try {
            // Wait until the future is finished and stop the wait if there are some ProcessCanceledException.
            waitUntilDone(future, psiFile);
        } catch (ProcessCanceledException ignore) {//Since 2024.2 ProcessCanceledException extends CancellationException so we can't use multicatch to keep backward compatibility
            //TODO delete block when minimum required version is 2024.2
            return;
        } catch (CancellationException ignore) {
            return;
        } catch (ExecutionException e) {
            LOGGER.error("Error while consuming LSP 'textDocument/completion' request", e);
            return;
        }

        ProgressManager.checkCanceled();
        if (isDoneNormally(future)) {
            List<CompletionData> data = future.getNow(Collections.emptyList());
            if (!data.isEmpty()) {
                CompletionPrefix completionPrefix = new CompletionPrefix(offset, document);
                for (var item : data) {
                    ProgressManager.checkCanceled();
                    addCompletionItems(psiFile, editor, completionPrefix, item.completion(), item.languageServer(), result);
                }
            }
        }
    }

    private static final CompletionItemComparator completionProposalComparator = new CompletionItemComparator();

    private void addCompletionItems(@NotNull PsiFile file,
                                    @NotNull Editor editor,
                                    @NotNull CompletionPrefix completionPrefix,
                                    @NotNull Either<List<CompletionItem>, CompletionList> completion,
                                    @NotNull LanguageServerItem languageServer,
                                    @NotNull CompletionResultSet result) {
        CompletionItemDefaults itemDefaults = null;
        List<CompletionItem> items = new ArrayList<>();
        if (completion.isLeft()) {
            items.addAll(completion.getLeft());
        } else {
            CompletionList completionList = completion.getRight();
            itemDefaults = completionList.getItemDefaults();
            items.addAll(completionList.getItems());
        }

        // Sort by item.sortText
        items.sort(completionProposalComparator);
        int size = items.size();

        //Items now sorted by priority, low index == high priority
        for (int i = 0; i < size; i++) {
            var item = items.get(i);
            if (StringUtils.isBlank(item.getLabel())) {
                // Invalid completion Item, ignore it
                continue;
            }
            ProgressManager.checkCanceled();
            // Create lookup item
            var lookupItem = createLookupItem(file, editor, completionPrefix.getCompletionOffset(), item, itemDefaults, languageServer);

            var prioritizedLookupItem = PrioritizedLookupElement.withPriority(lookupItem, size - i);
            // Compute the prefix
            var textEditRange = lookupItem.getTextEditRange();
            String prefix = textEditRange != null ? completionPrefix.getPrefixFor(textEditRange, item) : null;
            if (prefix != null) {
                // Add the IJ completion item (lookup item) by using the computed prefix
                result.withPrefixMatcher(prefix)
                        .caseInsensitive() // set case-insensitive to search Java class which starts with upper case
                        .addElement(prioritizedLookupItem);
            } else {
                // Should happen rarely, only when text edit is for multi-lines or if completion is triggered outside the text edit range.
                // Add the IJ completion item (lookup item) which will use the IJ prefix
                result.addElement(prioritizedLookupItem);
            }
        }
    }

    private LSPCompletionProposal createLookupItem(@NotNull PsiFile file,
                                                   @NotNull Editor editor,
                                                   int completionOffset,
                                                   @NotNull CompletionItem item,
                                                   @Nullable CompletionItemDefaults itemDefaults,
                                                   @NotNull LanguageServerItem languageServer) {
        // Update text edit range with item defaults if needed
        updateWithItemDefaults(item, itemDefaults);
        return new LSPCompletionProposal(file, editor, completionOffset, item, languageServer, this);
    }

    private static void updateWithItemDefaults(@NotNull CompletionItem item,
                                               @Nullable CompletionItemDefaults itemDefaults) {
        if (itemDefaults == null) {
            return;
        }
        String itemText = item.getTextEditText();
        if (itemDefaults.getEditRange() != null && itemText != null) {
            if (itemDefaults.getEditRange().isLeft()) {
                Range defaultRange = itemDefaults.getEditRange().getLeft();
                if (defaultRange != null) {
                    item.setTextEdit(Either.forLeft(new TextEdit(defaultRange, itemText)));
                }
            } else {
                InsertReplaceRange defaultInsertReplaceRange = itemDefaults.getEditRange().getRight();
                if (defaultInsertReplaceRange != null) {
                    item.setTextEdit(Either.forRight(new InsertReplaceEdit(itemText, defaultInsertReplaceRange.getInsert(), defaultInsertReplaceRange.getReplace())));
                }
            }
        }
    }


    /**
     * LSP lookup listener to track the selected completion item
     * and resolve if needed the LSP completionItem to get the detail
     * only for the selected completion item.
     */
    public static class LSPLookupManagerListener implements LookupManagerListener {

        @Override
        public void activeLookupChanged(@Nullable Lookup oldLookup, @Nullable Lookup newLookup) {
            if (newLookup == null) {
                return;
            }
            newLookup.addLookupListener(new LookupListener() {
                @Override
                public void currentItemChanged(@NotNull LookupEvent event) {
                    var item = event.getItem();
                    if (item == null) {
                        return;
                    }
                    if (item.getObject() instanceof LSPCompletionProposal lspCompletionProposal) {
                        // It is an LSP completion proposal
                        if (newLookup instanceof LookupImpl lookupImpl && lspCompletionProposal.needToResolveCompletionDetail()) {
                            // The LSP completion item requires to resolve completionItem to get the detail
                            // Refresh the lookup item
                            lookupImpl.scheduleItemUpdate(item);
                        }
                    }
                }
            });
        }
    }

}