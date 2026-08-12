package com.modelrag.search.orchestrator;

import com.modelrag.common.vector.SearchRequest;
import com.modelrag.common.vector.SearchResult;
import com.modelrag.common.vector.VectorStore;
import com.modelrag.indexing.service.EmbeddingService;
import com.modelrag.search.channel.Bm25Search;
import com.modelrag.search.dto.HybridSearchRequest;
import com.modelrag.search.dto.ScoredChunk;
import com.modelrag.search.dto.SearchStages;
import com.modelrag.search.facade.SearchFacade;
import com.modelrag.search.reranker.Reranker;
import com.modelrag.search.rewrite.QueryRewriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

@Service
public class SearchOrchestrator implements SearchFacade {
    private final VectorStore vectors; private final EmbeddingService embeddings; private final Bm25Search bm25; private final Reranker reranker; private final QueryRewriter rewriter; private final double vectorWeight; private final double bm25Weight; private final MeterRegistry metrics;
    public SearchOrchestrator(VectorStore v,EmbeddingService e,Bm25Search b,Reranker r,QueryRewriter rewriter,@Value("${modelrag.search.rrf-vector-weight:.7}") double vectorWeight,@Value("${modelrag.search.rrf-bm25-weight:.3}") double bm25Weight,MeterRegistry metrics){vectors=v;embeddings=e;bm25=b;reranker=r;this.rewriter=rewriter;this.vectorWeight=vectorWeight;this.bm25Weight=bm25Weight;this.metrics=metrics;}
    public SearchStages inspect(HybridSearchRequest req){long started=System.nanoTime();try{int topK=Math.max(1,req.topK());int recall=Math.max(topK*2,10);var ext=rewriter.expand(req.query());CompletableFuture<List<ScoredChunk>> vectorFuture=CompletableFuture.supplyAsync(()->retrieveVector(req.datasetId(),ext.searchQueries(),recall));CompletableFuture<List<ScoredChunk>> lexicalFuture=CompletableFuture.supplyAsync(()->retrieveBm25(req,ext.searchQueries(),topK,recall));List<ScoredChunk> vector=join(vectorFuture);List<ScoredChunk> lexical=join(lexicalFuture);Map<Long,Double> rrf=new HashMap<>();Map<Long,String> text=new HashMap<>();add(rrf,text,vector,vectorWeight);add(rrf,text,lexical,bm25Weight);List<ScoredChunk> fused=rank(rrf.entrySet().stream().map(e->new ScoredChunk(e.getKey(),text.get(e.getKey()),e.getValue(),"rrf",0)).sorted(Comparator.comparingDouble(ScoredChunk::score).reversed()).limit(20).toList());List<ScoredChunk> reranked=reranker.rerank(req.datasetId(),ext.rerankQuery(),fused);List<ScoredChunk> thresholded=threshold(reranked,req.threshold(),reranker.enabled());List<ScoredChunk> finalResults=rank(selectContext(thresholded,topK,reranker.enabled()));record("vector",vector.size());record("bm25",lexical.size());record("fused",fused.size());record(reranker.enabled()?"rerank":"local_rerank",reranked.size());record("threshold",thresholded.size());record("context",finalResults.size());return new SearchStages(ext.rewrittenQuery(),ext.searchQueries(),ext.rerankQuery(),vector,lexical,fused,reranker.enabled()?thresholded:List.of(),reranker.enabled(),finalResults);}finally{metrics.timer("modelrag.retrieval.latency","stage","inspect").record(System.nanoTime()-started,TimeUnit.NANOSECONDS);}}
    private List<ScoredChunk> retrieveVector(long datasetId,List<String> queries,int recall){return rank(unique(queries.stream().flatMap(query->rankVector(vectors.search(new SearchRequest(datasetId,embeddings.embed(datasetId,query),recall))).stream()).toList()));}
    private List<ScoredChunk> retrieveBm25(HybridSearchRequest req,List<String> queries,int topK,int recall){return rank(unique(queries.stream().flatMap(query->bm25.search(new HybridSearchRequest(req.datasetId(),query,topK,req.threshold()),recall).stream()).toList()));}
    private List<ScoredChunk> join(CompletableFuture<List<ScoredChunk>> future){try{return future.join();}catch(CompletionException error){Throwable cause=error.getCause();if(cause instanceof RuntimeException runtime)throw runtime;throw error;}}
    private List<ScoredChunk> rankVector(List<SearchResult> results){List<ScoredChunk> output=new ArrayList<>();for(int i=0;i<results.size();i++){SearchResult item=results.get(i);output.add(new ScoredChunk(item.chunkId(),item.content(),item.score(),"vector",i+1));}return List.copyOf(output);}
    private List<ScoredChunk> rank(List<ScoredChunk> results){List<ScoredChunk> output=new ArrayList<>();for(int i=0;i<results.size();i++){ScoredChunk item=results.get(i);output.add(new ScoredChunk(item.chunkId(),item.content(),item.score(),item.channel(),i+1));}return List.copyOf(output);}
    private List<ScoredChunk> unique(List<ScoredChunk> results){Map<Long,ScoredChunk> unique=new LinkedHashMap<>();for(ScoredChunk item:results){ScoredChunk old=unique.get(item.chunkId());if(old==null||item.score()>old.score())unique.put(item.chunkId(),item);}return unique.values().stream().sorted(Comparator.comparingDouble(ScoredChunk::score).reversed()).toList();}
    private void add(Map<Long,Double> fused,Map<Long,String> text,List<ScoredChunk> result,double weight){for(int i=0;i<result.size();i++){ScoredChunk item=result.get(i);fused.merge(item.chunkId(),weight/(60+i+1),Double::sum);text.put(item.chunkId(),item.content());}}
    private List<ScoredChunk> threshold(List<ScoredChunk> results,double threshold,boolean rerankApplied){if(threshold<=0)return results;if(rerankApplied)return results.stream().filter(item->item.score()>=threshold).toList();double max=results.stream().mapToDouble(ScoredChunk::score).max().orElse(0);if(max<=0)return List.of();return results.stream().filter(item->item.score()/max>=threshold).toList();}
    private List<ScoredChunk> selectContext(List<ScoredChunk> results,int topK,boolean rerankApplied){if(results.isEmpty())return List.of();int limit=Math.min(topK,results.size());ScoredChunk best=results.get(0);if(limit==1||!rerankApplied)return List.of(best);double second=results.size()>1?results.get(1).score():0;double gap=best.score()-second;double relativeGap=best.score()==0?0:gap/Math.abs(best.score());if(best.score()>=4&&relativeGap>=.08)return List.of(best);double cutoff=Math.max(best.score()*.92,best.score()-1.2);List<ScoredChunk> selected=results.stream().limit(limit).filter(item->item.score()>=cutoff).toList();return selected.isEmpty()?List.of(best):selected;}
    private void record(String stage,int count){metrics.counter("modelrag.retrieval.candidates","stage",stage).increment(count);if(count==0)metrics.counter("modelrag.retrieval.empty","stage",stage).increment();}
}
