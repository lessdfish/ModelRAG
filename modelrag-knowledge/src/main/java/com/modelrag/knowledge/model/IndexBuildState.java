package com.modelrag.knowledge.model;

/** Explicit lifecycle states for an immutable V2 retrieval build. */
public enum IndexBuildState {
    CREATED,
    PARSING,
    STRUCTURE_READY,
    UNIT_BUILDING,
    UNIT_READY,
    VECTOR_BUILDING,
    VECTOR_READY,
    LEXICAL_SYNCING,
    VERIFYING,
    READY,
    ACTIVE,
    SUPERSEDED,
    FAILED;

    public boolean canTransitionTo(IndexBuildState next) {
        if (next == null) return false;
        return switch (this) {
            case CREATED -> next == PARSING || next == FAILED;
            case PARSING -> next == STRUCTURE_READY || next == FAILED;
            case STRUCTURE_READY -> next == UNIT_BUILDING || next == FAILED;
            case UNIT_BUILDING -> next == UNIT_READY || next == FAILED;
            case UNIT_READY -> next == VECTOR_BUILDING || next == FAILED;
            case VECTOR_BUILDING -> next == VECTOR_READY || next == FAILED;
            case VECTOR_READY -> next == LEXICAL_SYNCING || next == FAILED;
            case LEXICAL_SYNCING -> next == VERIFYING || next == FAILED;
            case VERIFYING -> next == READY || next == FAILED;
            case READY -> next == ACTIVE || next == FAILED;
            case ACTIVE -> next == SUPERSEDED;
            case SUPERSEDED, FAILED -> false;
        };
    }
}
