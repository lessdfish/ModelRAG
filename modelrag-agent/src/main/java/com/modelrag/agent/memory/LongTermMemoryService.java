package com.modelrag.agent.memory;

import com.modelrag.indexing.service.EmbeddingService;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Importance-gated memory with BGE vectors in PostgreSQL when available. */
@Service public class LongTermMemoryService {
    public record Memory(String userId,String type,String content,double importance,double confidence,Instant expiresAt){}
    private final Map<String,List<Memory>> values=new ConcurrentHashMap<>(); private final ObjectProvider<JdbcTemplate> jdbc; private final EmbeddingService embeddings;
    public LongTermMemoryService(ObjectProvider<JdbcTemplate> jdbc,EmbeddingService embeddings){this.jdbc=jdbc;this.embeddings=embeddings;}
    public void upsert(Memory memory){if(memory.importance()<.6)return;String key=conflictKey(memory.content());values.computeIfAbsent(memory.userId(),ignored->new ArrayList<>()).removeIf(m->m.type().equals(memory.type())&&conflictKey(m.content()).equals(key));values.get(memory.userId()).add(memory);JdbcTemplate db=jdbc.getIfAvailable();if(db!=null)try{db.update("DELETE FROM kb_user_memory WHERE user_id=? AND memory_type=? AND content LIKE ?",memory.userId(),memory.type(),key+"：%");db.update("INSERT INTO kb_user_memory(user_id,memory_type,content,importance,confidence,expire_at,embedding) VALUES (?,?,?,?,?,?,CAST(? AS vector))",memory.userId(),memory.type(),memory.content(),memory.importance(),memory.confidence(),memory.expiresAt()==null?null:java.sql.Timestamp.from(memory.expiresAt()),vector(embeddings.embed(0,memory.content())));}catch(Exception ignored){}}
    public List<Memory> retrieveRelevant(String userId,String query,int topK){JdbcTemplate db=jdbc.getIfAvailable();if(db!=null)try{return db.query("SELECT user_id,memory_type,content,importance,confidence,expire_at FROM kb_user_memory WHERE user_id=? AND (expire_at IS NULL OR expire_at>NOW()) AND embedding IS NOT NULL ORDER BY embedding <=> CAST(? AS vector), importance DESC LIMIT ?",(rs,n)->new Memory(rs.getString("user_id"),rs.getString("memory_type"),rs.getString("content"),rs.getDouble("importance"),rs.getDouble("confidence"),rs.getTimestamp("expire_at")==null?null:rs.getTimestamp("expire_at").toInstant()),userId,vector(embeddings.embed(0,query)),topK);}catch(Exception ignored){}return values.getOrDefault(userId,List.of()).stream().filter(m->m.expiresAt()==null||m.expiresAt().isAfter(Instant.now())).filter(m->relevance(query,m.content())>0).sorted(Comparator.<Memory>comparingDouble(m->relevance(query,m.content())).thenComparingDouble(Memory::importance).reversed()).limit(topK).toList();}
    public String promptContext(String userId,String query,int topK){List<Memory> memories=new ArrayList<>();memories.addAll(retrieveRelevant("global",query,topK));if(userId!=null&&!userId.isBlank()&&!userId.equals("global"))memories.addAll(retrieveRelevant(userId,query,topK));LinkedHashSet<String> lines=new LinkedHashSet<>();for(Memory memory:memories)lines.add("- "+memory.type()+" "+memory.content());return lines.isEmpty()?"":"长期记忆（仅作辅助上下文，若与知识库证据冲突，以知识库证据为准）：\n"+String.join("\n",lines);}
    private String conflictKey(String content){int index=content==null?-1:content.indexOf('：');return index>0?content.substring(0,index):String.valueOf(content);}
    private int relevance(String query,String content){String q=query==null?"":query.replaceAll("[\\s，。！？、：:；;（）()]+","").toLowerCase();String text=content==null?"":content.replaceAll("[\\s，。！？、：:；;（）()]+","").toLowerCase();if(q.isBlank()||text.isBlank())return 0;if(text.contains(q)||q.contains(text))return Math.min(q.length(),text.length());int score=0;for(int i=0;i+1<q.length();i++)if(text.contains(q.substring(i,i+2)))score++;return score;}
    private String vector(float[] values){StringBuilder result=new StringBuilder("[");for(int i=0;i<values.length;i++){if(i>0)result.append(',');result.append(values[i]);}return result.append(']').toString();}
}
