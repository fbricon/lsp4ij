/*******************************************************************************
 * Copyright (c) 2024 Red Hat, Inc.
 * Distributed under license by Red Hat, Inc. All rights reserved.
 * This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors:
 * Red Hat, Inc. - initial API and implementation
 ******************************************************************************/
package com.redhat.devtools.lsp4ij.fixtures;

import com.intellij.codeInsight.lookup.LookupElement;
import com.redhat.devtools.lsp4ij.JSONUtils;
import com.redhat.devtools.lsp4ij.mock.MockLanguageServer;
import org.eclipse.lsp4j.CompletionList;
import org.jetbrains.annotations.NotNull;

import java.util.stream.Stream;

/**
 * Base class test case to test LSP 'textDocument/completion' feature.
 */
public abstract class LSPCompletionFixtureTestCase extends LSPCodeInsightFixtureTestCase {

    /**
     * Test LSP completion.
     *
     * @param fileName           the file name used to match registered language servers.
     * @param editorContentText  the editor content text.
     * @param jsonCompletionList the LSP CompletionList as JSON string.
     * @param expectedItems      the expected IJ lookupItem string.
     */
    protected void assertCompletion(@NotNull String fileName,
                                    @NotNull String editorContentText,
                                    @NotNull String jsonCompletionList,
                                    @NotNull String... expectedItems) {
        assertCompletion(fileName, editorContentText, JSONUtils.getLsp4jGson().fromJson(jsonCompletionList, CompletionList.class), expectedItems);
    }

    /**
     * Test LSP completion.
     *
     * @param fileName          the file name used to match registered language servers.
     * @param editorContentText the editor content text.
     * @param completionList    the LSP CompletionList.
     * @param expectedItems     the expected IJ lookupItem string.
     */
    protected void assertCompletion(@NotNull String fileName,
                                    @NotNull String editorContentText,
                                    @NotNull CompletionList completionList,
                                    @NotNull String... expectedItems) {
        MockLanguageServer.INSTANCE.setTimeToProceedQueries(5000);
        MockLanguageServer.INSTANCE.setCompletionList(completionList);
        // Open editor for a given file name and content (which declares <caret> to know where the completion is triggered).
        myFixture.configureByText(fileName, editorContentText);
        // Process completion
        myFixture.completeBasic();
        if (expectedItems == null || expectedItems.length == 0) {
            assertNull(myFixture.getLookup());
        } else {
            assertNotNull(myFixture.getLookup());
            var actualItems = Stream.of(myFixture.getLookupElements())
                    .map(LookupElement::getLookupString)
                    .toList();
            for (var expectedItem : expectedItems) {
                assertContainsElements(actualItems, expectedItem);
            }
        }
    }

    /**
     * Test LSP apply completion.
     *
     * @param selectedItemIndex         the selected index of lookup elements.
     * @param expectedEditorContentText the expected editor content text.
     */
    public void assertApplyCompletionItem(int selectedItemIndex,
                                          @NotNull String expectedEditorContentText) {
        myFixture.selectItem(myFixture.getLookupElements()[selectedItemIndex]);
        assertEquals(expectedEditorContentText, myFixture.getEditor().getDocument().getText());
    }
}
