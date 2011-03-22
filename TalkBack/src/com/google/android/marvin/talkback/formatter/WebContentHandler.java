// Copyright 2010 Google Inc. All Rights Reserved.

package com.google.android.marvin.talkback.formatter;

import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

import java.util.HashMap;
import java.util.Stack;

/**
 * A handler for parsing simple HTML from WebView
 *
 * @author credo@google.com (Tim Credo)
 */
public class WebContentHandler extends DefaultHandler {

    static final String INPUT_TAG = "input";

    /**
     * Maps input type attribute to element description.
     */
    private HashMap<String, String> mInputTypeToDesc;

    /**
     * Maps ARIA role attribute to element description.
     */
    private HashMap<String, String> mAriaRoleToDesc;

    /**
     * Map tags to element description.
     */
    private HashMap<String, String> mTagToDesc;

    /**
     * A stack for storing post-order text generated by opening tags.
     */
    private Stack<String> mPostorderTextStack;

    /**
     * Builder for a string to be spoken based on parsed HTML.
     */
    private StringBuilder mOutputBuilder;

    /**
     * Initializes the handler with maps that provide descriptions for relevant
     * features in HTML.
     *
     * @param htmlInputMap A mapping from input types to text descriptions.
     * @param htmlRoleMap A mapping from ARIA roles to text descriptions.
     * @param htmlTagMap A mapping from common tags to text descriptions.
     */
    public WebContentHandler(HashMap<String, String> htmlInputMap,
            HashMap<String, String> htmlRoleMap, HashMap<String, String> htmlTagMap) {
        super();
        mInputTypeToDesc = htmlInputMap;
        mAriaRoleToDesc = htmlRoleMap;
        mTagToDesc = htmlTagMap;
    }

    @Override
    public void startDocument() {
        mOutputBuilder = new StringBuilder();
        mPostorderTextStack = new Stack<String>();
    }

    /**
     * Depending on the type of element, generate text describing its conceptual
     * value and role and add it to the output. The role text is spoken after
     * any content, so it is added to the stack to wait for the closing tag.
     */
    @Override
    public void startElement(String uri, String localName, String name, Attributes attributes) {
        fixWhiteSpace();
        String ariaLabel = attributes.getValue("aria-label");
        String alt = attributes.getValue("alt");
        String title = attributes.getValue("title");
        if (ariaLabel != null) {
            mOutputBuilder.append(ariaLabel);
        } else if (alt != null) {
            mOutputBuilder.append(alt);
        } else if (title != null) {
            mOutputBuilder.append(title);
        }
        String value = attributes.getValue("value");
        if (value != null) {
            if (!localName.equalsIgnoreCase("checkbox") && !localName.equalsIgnoreCase("radio")) {
                fixWhiteSpace();
                mOutputBuilder.append(value);
            }
        }
        /*
         * Add role text to the stack so it appears after the content. If there
         * is no text we still need to push a blank string, since this will pop
         * when this element ends.
         */
        String role = attributes.getValue("role");
        String roleName = mAriaRoleToDesc.get(role);
        String type = attributes.getValue("type");
        String tagInfo = mTagToDesc.get(localName.toLowerCase());
        if (roleName != null) {
            mPostorderTextStack.push(roleName);
        } else if (localName.equalsIgnoreCase(INPUT_TAG) && type != null) {
            String typeInfo = mInputTypeToDesc.get(type.toLowerCase());
            if (typeInfo != null) {
                mPostorderTextStack.push(typeInfo);
            } else {
                mPostorderTextStack.push("");
            }
        } else if (tagInfo != null) {
            mPostorderTextStack.push(tagInfo);
        } else {
            mPostorderTextStack.push("");
        }
    }

    /**
     * Character data is passed directly to output.
     */
    @Override
    public void characters(char[] ch, int start, int length) {
        mOutputBuilder.append(ch, start, length);
    }

    /**
     * After the end of an element, get the post-order text from the stack and
     * add it to the output.
     */
    @Override
    public void endElement(String uri, String localName, String name) {
        fixWhiteSpace();
        mOutputBuilder.append(mPostorderTextStack.pop());
    }

    /**
     * Ensure the output string has a character of whitespace before adding
     * another word.
     */
    public void fixWhiteSpace() {
        int index = mOutputBuilder.length() - 1;
        if (index >= 0) {
            char lastCharacter = mOutputBuilder.charAt(index);
            if (!Character.isWhitespace(lastCharacter)) {
                mOutputBuilder.append(" ");
            }
        }
    }

    /**
     * Get the processed string in mBuilder. Call this after parsing is done to
     * get the finished output.
     *
     * @return A string with HTML tags converted to descriptions suitable for
     *         speaking.
     */
    public String getOutput() {
        return mOutputBuilder.toString();
    }
}
