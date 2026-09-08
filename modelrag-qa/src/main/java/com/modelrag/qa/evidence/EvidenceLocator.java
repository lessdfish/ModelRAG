package com.modelrag.qa.evidence;

/** Source location carried by evidence without inventing missing page information. */
public record EvidenceLocator(String titlePath, Integer pageFrom, Integer pageTo,
        Long charStart, Long charEnd) {
    public EvidenceLocator {
        titlePath = titlePath == null ? "" : titlePath.trim();
        if ((pageFrom == null) != (pageTo == null)
                || (pageFrom != null && (pageFrom <= 0 || pageTo < pageFrom))) {
            throw new IllegalArgumentException("evidence page range is invalid");
        }
        if ((charStart == null) != (charEnd == null)
                || (charStart != null && (charStart < 0 || charEnd < charStart))) {
            throw new IllegalArgumentException("evidence character range is invalid");
        }
    }

    public static EvidenceLocator empty() {
        return new EvidenceLocator("", null, null, null, null);
    }
}
