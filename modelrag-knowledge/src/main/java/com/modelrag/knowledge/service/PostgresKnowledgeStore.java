package com.modelrag.knowledge.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelrag.common.exception.BusinessException;
import com.modelrag.common.exception.ErrorCode;
import com.modelrag.knowledge.model.Chunk;
import com.modelrag.knowledge.model.Dataset;
import com.modelrag.knowledge.model.Document;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** PostgreSQL-backed implementation used with the postgres Spring profile. */
@Service
@Profile("postgres")
public class PostgresKnowledgeStore extends KnowledgeStore {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final JdbcTemplate jdbc;
    public PostgresKnowledgeStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public Dataset createDataset(String name, String description, Integer size, Integer overlap, Integer topK, Double threshold) {
        if (name == null || name.isBlank()) throw new BusinessException(ErrorCode.VALIDATION, "知识库名称不能为空");
        int chunkSize = chunkSize(size); int chunkOverlap = chunkOverlap(overlap, chunkSize);
        Long id = jdbc.queryForObject("INSERT INTO kb_dataset(name,description,chunk_size,chunk_overlap,top_k,similarity_threshold) VALUES (?,?,?,?,?,?) RETURNING id", Long.class, name.trim(), description, chunkSize, chunkOverlap, topK(topK), threshold(threshold));
        return dataset(id);
    }
    @Override public List<Dataset> datasets() { return jdbc.query("SELECT * FROM kb_dataset WHERE delete_time IS NULL ORDER BY id", (rs,n)->dataset(rs)); }
    @Override public Dataset dataset(long id) { List<Dataset> rows=jdbc.query("SELECT * FROM kb_dataset WHERE id=? AND delete_time IS NULL",(rs,n)->dataset(rs),id);if(rows.isEmpty())throw new BusinessException(ErrorCode.NOT_FOUND,"知识库不存在");return rows.get(0); }
    @Override public Dataset updateDataset(long id,String name,String description,Integer size,Integer overlap,Integer topK,Double threshold){dataset(id);if(name==null||name.isBlank())throw new BusinessException(ErrorCode.VALIDATION,"知识库名称不能为空");int chunkSize=chunkSize(size);int chunkOverlap=chunkOverlap(overlap,chunkSize);jdbc.update("UPDATE kb_dataset SET name=?,description=?,chunk_size=?,chunk_overlap=?,top_k=?,similarity_threshold=?,revision=revision+1,update_time=NOW() WHERE id=?",name.trim(),description,chunkSize,chunkOverlap,topK(topK),threshold(threshold),id);return dataset(id);}
    @Override public void deleteDataset(long id){dataset(id);jdbc.update("UPDATE kb_dataset SET delete_time=NOW(),update_time=NOW() WHERE id=?",id);jdbc.update("UPDATE kb_document SET delete_time=NOW(),update_time=NOW() WHERE dataset_id=? AND delete_time IS NULL",id);jdbc.update("UPDATE kb_chunk SET delete_time=NOW() WHERE dataset_id=? AND delete_time IS NULL",id);}
    @Override public Document addDocument(long datasetId,String name,String type,String hash,String content) { dataset(datasetId);if(!jdbc.query("SELECT id FROM kb_document WHERE dataset_id=? AND file_hash=? AND delete_time IS NULL",(rs,n)->rs.getLong(1),datasetId,hash).isEmpty())throw new BusinessException(ErrorCode.DUPLICATE_DOCUMENT,"同一知识库中已有相同文档");Long id=jdbc.queryForObject("INSERT INTO kb_document(dataset_id,file_name,file_type,file_hash,source_content) VALUES (?,?,?,?,?) RETURNING id",Long.class,datasetId,name,type,hash,content);bumpDatasetRevision(datasetId);return document(id); }
    @Override public Document document(long id) { List<Document> rows=jdbc.query("SELECT * FROM kb_document WHERE id=? AND delete_time IS NULL",(rs,n)->document(rs),id);if(rows.isEmpty())throw new BusinessException(ErrorCode.NOT_FOUND,"文档不存在");return rows.get(0); }
    @Override public void deleteDocument(long datasetId,long documentId){Document document=document(documentId);if(document.datasetId()!=datasetId)throw new BusinessException(ErrorCode.NOT_FOUND,"文档不存在");jdbc.update("UPDATE kb_document SET delete_time=NOW(),update_time=NOW() WHERE id=?",documentId);jdbc.update("UPDATE kb_chunk SET delete_time=NOW() WHERE document_id=? AND delete_time IS NULL",documentId);bumpDatasetRevision(datasetId);}
    @Override public void status(long id,String status,String error,int count){jdbc.update("UPDATE kb_document SET index_status=?,error_msg=?,chunk_count=?,update_time=NOW() WHERE id=?",status,error,count,id);}
    @Override public List<Document> documents(long datasetId){return jdbc.query("SELECT * FROM kb_document WHERE dataset_id=? AND delete_time IS NULL ORDER BY id",(rs,n)->document(rs),datasetId);}
    @Override public List<Chunk> chunks(long datasetId){return jdbc.query("""
            SELECT c.*
            FROM kb_chunk c
            JOIN kb_document d ON d.id=c.document_id
            WHERE c.dataset_id=?
              AND c.delete_time IS NULL
              AND d.delete_time IS NULL
              AND d.index_status='READY'
            ORDER BY c.document_id,c.chunk_index
            """,(rs,n)->chunk(rs),datasetId);}
    @Override public void chunks(long documentId,List<Chunk> chunks){jdbc.update("DELETE FROM kb_chunk WHERE document_id=?",documentId);for(Chunk c:chunks)jdbc.update("INSERT INTO kb_chunk(id,document_id,dataset_id,chunk_index,content,metadata,parent_chunk_id) VALUES (?,?,?,?,?,CAST(? AS jsonb),?)",c.id(),c.documentId(),c.datasetId(),c.index(),c.content(),metadata(c.metadata()),c.parentChunkId());bumpDatasetRevision(document(documentId).datasetId());}
    @Override public long nextId(){Long id=jdbc.queryForObject("SELECT nextval(pg_get_serial_sequence('kb_chunk','id'))",Long.class);return id;}
    @Override public Dataset bumpDatasetRevision(long datasetId){jdbc.update("UPDATE kb_dataset SET revision=revision+1,update_time=NOW() WHERE id=? AND delete_time IS NULL",datasetId);return dataset(datasetId);}
    private Dataset dataset(ResultSet rs)throws java.sql.SQLException{return new Dataset(rs.getLong("id"),rs.getString("name"),rs.getString("description"),rs.getInt("chunk_size"),rs.getInt("chunk_overlap"),rs.getInt("top_k"),rs.getDouble("similarity_threshold"),rs.getLong("revision"));}
    private Document document(ResultSet rs)throws java.sql.SQLException{return new Document(rs.getLong("id"),rs.getLong("dataset_id"),rs.getString("file_name"),rs.getString("file_type"),rs.getString("file_hash"),rs.getString("source_content"),rs.getString("index_status"),rs.getString("error_msg"),rs.getInt("chunk_count"));}
    private Chunk chunk(ResultSet rs)throws java.sql.SQLException{Long parent=rs.getObject("parent_chunk_id",Long.class);return new Chunk(rs.getLong("id"),rs.getLong("document_id"),rs.getLong("dataset_id"),rs.getInt("chunk_index"),rs.getString("content"),metadata(rs.getString("metadata")),parent);}
    private Map<String,String> metadata(String json){try{return JSON.readValue(json,new TypeReference<Map<String,String>>(){});}catch(Exception ignored){return Map.of();}}
    private String metadata(Map<String,String> metadata){return "{"+metadata.entrySet().stream().map(e->"\""+e.getKey()+"\":\""+e.getValue().replace("\"","\\\"")+"\"").collect(java.util.stream.Collectors.joining(","))+"}";}
}
