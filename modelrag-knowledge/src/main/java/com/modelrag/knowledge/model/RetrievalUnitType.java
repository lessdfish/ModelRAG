package com.modelrag.knowledge.model;

/** Retrieval projection strategies produced from a document node. */
public enum RetrievalUnitType {
    PARAGRAPH,
    WINDOW,
    SECTION,
    SECTION_SUMMARY,
    TABLE,
    TITLE_PATH
}
