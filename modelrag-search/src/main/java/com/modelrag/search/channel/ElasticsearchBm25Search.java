package com.modelrag.search.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelrag.search.dto.HybridSearchRequest;
import com.modelrag.search.dto.ScoredChunk;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("postgres")
public class ElasticsearchBm25Search implements Bm25Search {
    private final String endpoint;
    private final HttpClient http=HttpClient.newHttpClient();
    private final ObjectMapper json=new ObjectMapper();
    public ElasticsearchBm25Search(@Value("${modelrag.elasticsearch.endpoint:http://localhost:9200}")String endpoint){this.endpoint=endpoint.replaceAll("/$","");}
    public List<ScoredChunk> search(HybridSearchRequest request,int recall){try{String query=json.writeValueAsString(request.query());String body="""
            {"size":%d,
             "query":{"bool":{
               "filter":[
                 {"term":{"datasetId":%d}},
                 {"term":{"indexType":"default"}}
               ],
               "must":[{"multi_match":{
                 "query":%s,
                 "type":"best_fields",
                 "fields":["titlePath^4","documentName^2","content"],
                 "operator":"or"
               }}]
             }}}
            """.formatted(recall,request.datasetId(),query);HttpRequest call=HttpRequest.newBuilder(URI.create(endpoint+"/modelrag-chunks/_search")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();JsonNode hits=json.readTree(http.send(call,HttpResponse.BodyHandlers.ofString()).body()).path("hits").path("hits");List<ScoredChunk> result=new ArrayList<>();for(JsonNode hit:hits){JsonNode source=hit.path("_source");result.add(new ScoredChunk(source.path("chunkId").asLong(),source.path("content").asText(),hit.path("_score").asDouble(),"bm25",result.size()+1));}return result;}catch(Exception e){return List.of();}}
}
