package com.modelrag.knowledge.parser;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

public final class TextDocumentParsers {
    private TextDocumentParsers() { }
    public static DocumentParser forFile(String fileName) {
        String lower=fileName.toLowerCase();
        if (lower.endsWith(".pdf")) return new Pdf();
        if (lower.endsWith(".docx")) return new Docx();
        if (lower.endsWith(".md")) return new Plain("markdown");
        if (lower.endsWith(".txt")) return new Plain("text");
        throw new IllegalArgumentException("只支持 PDF、DOCX、MD、TXT 文件");
    }
    private static final class Plain implements DocumentParser { private final String name; Plain(String name){this.name=name;} public boolean supports(String f){return true;} public String parse(byte[] b){return new String(b, StandardCharsets.UTF_8);} public String name(){return name;} }
    private static final class Pdf implements DocumentParser { public boolean supports(String f){return f.endsWith(".pdf");} public String parse(byte[] b) throws Exception { try(var pdf=Loader.loadPDF(b)){ return new PDFTextStripper().getText(pdf); } } public String name(){return "pdfbox";} }
    private static final class Docx implements DocumentParser { public boolean supports(String f){return f.endsWith(".docx");} public String parse(byte[] b) throws Exception { try(var doc=new XWPFDocument(new ByteArrayInputStream(b))){return String.join("\n",doc.getParagraphs().stream().map(p->p.getText()).toList());} } public String name(){return "poi";} }
}
