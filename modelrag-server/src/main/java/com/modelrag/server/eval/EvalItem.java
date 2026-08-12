package com.modelrag.server.eval;
import java.util.List;
public record EvalItem(Long id,String question,List<Long> expectedChunkIds,String expectedAnswer,Boolean shouldRefuse,String category,String sourceTraceId,String failureStage) {
    public EvalItem(Long id,String question,List<Long> expectedChunkIds,String expectedAnswer,Boolean shouldRefuse,String category){this(id,question,expectedChunkIds,expectedAnswer,shouldRefuse,category,null,null);}
    public EvalItem(String question,List<Long> expectedChunkIds,String expectedAnswer,Boolean shouldRefuse,String category){this(null,question,expectedChunkIds,expectedAnswer,shouldRefuse,category,null,null);}
    public EvalItem(String question,List<Long> expectedChunkIds){this(null,question,expectedChunkIds,null,false,null,null,null);}
    public EvalItem withId(Long id){return new EvalItem(id,question,expectedChunkIds,expectedAnswer,shouldRefuse,category,sourceTraceId,failureStage);}
}
