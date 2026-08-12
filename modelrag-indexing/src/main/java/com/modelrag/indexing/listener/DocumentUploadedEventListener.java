package com.modelrag.indexing.listener;
import com.modelrag.common.event.DocumentUploadedEvent; import com.modelrag.indexing.pipeline.IndexingPipeline; import org.springframework.context.event.EventListener; import org.springframework.scheduling.annotation.Async; import org.springframework.stereotype.Component;
@Component public class DocumentUploadedEventListener {private final IndexingPipeline pipeline;public DocumentUploadedEventListener(IndexingPipeline p){pipeline=p;}@Async("indexingExecutor")@EventListener public void on(DocumentUploadedEvent event){pipeline.index(event.documentId());}}
