package com.modelrag.knowledge.service;
import com.modelrag.common.event.DocumentUploadedEvent;
import com.modelrag.knowledge.model.Document;
import com.modelrag.knowledge.parser.TextDocumentParsers;
import java.security.MessageDigest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
@Service public class DocumentService { private final KnowledgeStore store; private final ApplicationEventPublisher events; public DocumentService(KnowledgeStore store,ApplicationEventPublisher events){this.store=store;this.events=events;} public Document upload(long datasetId,MultipartFile file)throws Exception{if(file==null||file.isEmpty())throw new IllegalArgumentException("上传文件不能为空");var parser=TextDocumentParsers.forFile(file.getOriginalFilename());byte[] data=file.getBytes();String content=parser.parse(data);if(content.isBlank())throw new IllegalArgumentException("文档未解析出可索引文本");String hash=hex(MessageDigest.getInstance("SHA-256").digest(data));Document document=store.addDocument(datasetId,file.getOriginalFilename(),type(file.getOriginalFilename()),hash,content);events.publishEvent(new DocumentUploadedEvent(this,document.id(),datasetId));return document;} private String type(String n){int x=n.lastIndexOf('.');return x<0?"":n.substring(x+1).toUpperCase();}private String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte x:b)s.append(String.format("%02x",x));return s.toString();}}
