package com.modelrag.qa.evidence;

/** How a runtime evidence item entered the bounded EvidenceSet. */
public enum EvidenceOrigin {
    RETRIEVAL,
    PARENT,
    PREVIOUS,
    NEXT,
    CHILD,
    REFERENCE
}
