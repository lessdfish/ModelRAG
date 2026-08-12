package com.modelrag.agent.orchestrator;

import com.modelrag.agent.approval.ApprovalGate;
import com.modelrag.agent.approval.ApprovalRecord;
import com.modelrag.agent.intent.IntentNode;
import com.modelrag.agent.intent.IntentTreeService;
import com.modelrag.agent.memory.ConversationMemory;
import com.modelrag.agent.memory.LongTermMemoryService;
import com.modelrag.agent.router.ComplexityRouter;
import com.modelrag.agent.router.RouteDecision;
import com.modelrag.agent.safety.LoopDetector;
import com.modelrag.agent.tool.ToolDefinition;
import com.modelrag.agent.tool.HttpToolInvoker;
import com.modelrag.agent.tool.ResilientToolExecutor;
import com.modelrag.agent.tool.ToolRegistry;
import com.modelrag.agent.trace.ToolCallTrace;
import com.modelrag.agent.trace.ToolCallTracer;
import com.modelrag.agent.trace.AgentStepTracer;
import com.modelrag.common.dto.SseEvent;
import com.modelrag.common.sse.SseEmitterService;
import com.modelrag.knowledge.service.KnowledgeStore;
import com.modelrag.qa.dto.Citation;
import com.modelrag.qa.dto.QaRequest;
import com.modelrag.qa.dto.QaResult;
import com.modelrag.qa.orchestrator.QaOrchestrator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class AgentOrchestrator {
    private static final int MAX_REACT_STEPS = 20;
    private final ComplexityRouter router; private final QaOrchestrator qa; private final ApprovalGate approvals;
    private final ToolRegistry tools; private final LoopDetector loops; private final ConversationMemory memory; private final LongTermMemoryService longTermMemory; private final SseEmitterService sse; private final ToolCallTracer tracer; private final AgentStepTracer stepTracer; private final IntentTreeService intents; private final ResilientToolExecutor executor; private final HttpToolInvoker httpTools; private final AgentPlanner planner; private final KnowledgeStore store; private final AgentExecutionRegistry executions;
    private final Map<String,List<String>> actions = new ConcurrentHashMap<>();
    public AgentOrchestrator(ComplexityRouter r,QaOrchestrator q,ApprovalGate a,ToolRegistry t,LoopDetector l,ConversationMemory m,LongTermMemoryService lm,SseEmitterService s,ToolCallTracer tr,AgentStepTracer stepTracer,IntentTreeService i,ResilientToolExecutor e,HttpToolInvoker h,AgentPlanner planner,KnowledgeStore store,AgentExecutionRegistry executions){router=r;qa=q;approvals=a;tools=t;loops=l;memory=m;longTermMemory=lm;sse=s;tracer=tr;this.stepTracer=stepTracer;intents=i;executor=e;httpTools=h;this.planner=planner;this.store=store;this.executions=executions;}
    public AgentResult execute(QaRequest req){
        String id=UUID.randomUUID().toString(); IntentNode intent=intents.match(req.datasetId(),req.query()).orElse(null); RouteDecision detectedRoute=intent==null?router.route(req.query()):"TOOL".equals(intent.targetType())?RouteDecision.AGENT:RouteDecision.DIRECT_RAG; RouteDecision route=RouteDecision.AGENT;
        executions.register(id,req.userId(),req.datasetId(),req.conversationId());
        List<String> steps=new ArrayList<>(); steps.add("THINK");
        publish(id,"THINK","正在理解请求",Map.of("query",req.query(),"detectedRoute",detectedRoute.name(),"reactMode","FULL"));
        boolean lockedTool=intent!=null&&"TOOL".equals(intent.targetType())&&intent.targetId()!=null;
        boolean highRisk=queryIsHighRisk(req.query());
        String fallbackTool=lockedTool?intent.targetId():highRisk?"destructive_operation":"knowledge_lookup";
        List<String> parts=subtasks(req.query());
        ToolDefinition definition=tools.get(fallbackTool);
        steps.add("PLAN"); publish(id,"PLAN","进入完整 ReAct 循环",Map.of("tool",fallbackTool,"maxSteps",MAX_REACT_STEPS,"subtasks",parts,"plannerMode","REACT","thought","Agent 将逐步 Thought/Action/Observation 决策。","reactPhase","THOUGHT"));
        if("HIGH".equals(definition.riskLevel())){ApprovalRecord approval=approvals.request(id,fallbackTool,"{\"query\":\""+req.query().replace("\"","\\\"")+"\"}",req.userId(),req.datasetId(),req.conversationId());steps.add("APPROVAL_REQUIRED");rememberApprovalWait(req,approval.id());publish(id,"APPROVAL_REQUIRED","高风险工具需要审批",Map.of("approvalId",approval.id(),"ttl",300,"requesterUserId",req.userId(),"datasetId",req.datasetId(),"conversationId",req.conversationId()==null?"":req.conversationId()));return new AgentResult(id,route,"WAITING_APPROVAL",null,approval.id(),steps);}
        return reactLoop(id,route,req,null,fallbackTool,steps,true,parts);
    }
    public AgentResult continueAfterApproval(String approvalId,QaRequest req,boolean approved){return continueAfterApproval(approvalId,req,approved,req.userId());}
    public AgentResult continueAfterApproval(String approvalId,QaRequest req,boolean approved,String approverId){ApprovalRecord record=approvals.decide(approvalId,approved,approverId);if(!"APPROVED".equals(record.status())){String answer="工具调用未获批准。";rememberApprovalDecision(req,answer,null);publish(record.executionId(),"ERROR","工具调用未获批准",Map.of("status",record.status(),"approvedBy",record.approvedBy()));return new AgentResult(record.executionId(),RouteDecision.AGENT,record.status(),answer,record.id(),List.of("APPROVAL_"+record.status()),List.of(),0,true,null);}List<String> steps=new ArrayList<>(List.of("THINK","PLAN","APPROVAL_APPROVED"));return reactLoop(record.executionId(),RouteDecision.AGENT,req,record.id(),record.toolName(),steps,false,subtasks(req.query()));}
    private AgentResult reactLoop(String id,RouteDecision route,QaRequest req,String approvalId,String fallbackTool,List<String> steps,boolean rememberQuestion,List<String> parts){List<QaResult> results=new ArrayList<>();List<String> observations=new ArrayList<>();for(int i=0;i<MAX_REACT_STEPS;i++){AgentPlanner.Plan decision=planner.reactStep(req.query(),observations,fallbackTool,parts,availableTools(req),i);if(decision.subtasks().isEmpty()){steps.add("REACT_DONE");publish(id,"PLAN","ReAct 判断观察已足够",Map.of("plannerMode",decision.mode(),"thought",decision.thought(),"step",i+1,"reactPhase","THOUGHT"));break;}String tool=decision.toolName();String subtask=decision.subtasks().get(0);ToolDefinition definition=tools.get(tool);if("HIGH".equals(definition.riskLevel())&&approvalId==null){ApprovalRecord approval=approvals.request(id,tool,"{\"query\":\""+req.query().replace("\"","\\\"")+"\"}",req.userId(),req.datasetId(),req.conversationId());steps.add("APPROVAL_REQUIRED");rememberApprovalWait(req,approval.id());publish(id,"APPROVAL_REQUIRED","高风险工具需要审批",Map.of("approvalId",approval.id(),"ttl",300,"requesterUserId",req.userId(),"datasetId",req.datasetId(),"conversationId",req.conversationId()==null?"":req.conversationId()));return new AgentResult(id,route,"WAITING_APPROVAL",null,approval.id(),steps);}steps.add("PLAN");publish(id,"PLAN","ReAct Thought 已选择下一步",Map.of("tool",tool,"subtask",subtask,"plannerMode",decision.mode(),"thought",decision.thought(),"step",i+1,"maxSteps",MAX_REACT_STEPS,"reactPhase","THOUGHT"));String fingerprint=tool+":"+subtask;List<String> trace=actions.computeIfAbsent(id,ignored->new ArrayList<>());trace.add(fingerprint);if(loops.detect(trace)){steps.add("REACT_DONE");publish(id,"PLAN","ReAct 发现重复 Action，使用已有观察结束循环",Map.of("subtask",subtask,"step",i+1,"reactPhase","THOUGHT"));break;}long started=System.nanoTime();steps.add("ACT:"+tool);publish(id,"ACT","正在执行工具 "+tool,Map.of("toolName",tool,"subtask",subtask,"index",i+1,"reactPhase","ACTION"));String toolParams=toolParams(req,subtask);try{requireToolAccess(definition,req);QaRequest subRequest=new QaRequest(req.datasetId(),subtask,null,req.userId(),req.userRoles());var execution=executor.execute(definition,toolParams,()->executeTool(definition,subRequest));QaResult result=execution.value();results.add(result);steps.add("OBSERVE");String observation=observation(subtask,result);observations.add(observation);tracer.record(new ToolCallTrace(id,tool,toolParams,toolOutput(result,execution),true,null,(System.nanoTime()-started)/1_000_000));publish(id,"OBSERVE",result.refused()?"证据不足":"已获得证据",Map.of("traceId",safeTraceId(result),"refused",result.refused(),"reactPhase","OBSERVATION","attempts",execution.attempts(),"reused",execution.reused(),"step",i+1));}catch(RuntimeException e){tracer.record(new ToolCallTrace(id,tool,toolParams,"{}",false,e.getMessage(),(System.nanoTime()-started)/1_000_000));throw e;}}String answer=combine(results);String traceId=lastTraceId(results);List<Citation> citations=citations(results);double confidence=confidence(results);boolean refused=results.isEmpty()||results.stream().allMatch(QaResult::refused);rememberAgentAnswer(req,answer,traceId,citations,rememberQuestion);qa.recordAudit(req,answer,citationsJson(citations),confidence,refused,traceId.isBlank()?id:traceId,"agent");steps.add("ANSWER");publish(id,"ANSWER","已根据 ReAct 观察结果形成答案",Map.of("count",results.size(),"maxSteps",MAX_REACT_STEPS,"reactPhase","FINAL_ANSWER"));publish(id,"DONE","Agent 完成",Map.of("traceId",traceId));return new AgentResult(id,route,"DONE",answer,approvalId,steps,citations,confidence,refused,traceId);}
    private QaResult executeTool(ToolDefinition definition,QaRequest request){if(definition.http()){String output=httpTools.invoke(definition,request.query());return new QaResult("结论："+limit(output,500),List.of(),1,false,null);}return qa.answer(withContext(request));}
    private QaRequest withContext(QaRequest request){String query=request.query();if(request.conversationId()!=null)query=memory.contextualQuery(request.conversationId(),query);String longTerm=longTermMemory.promptContext(request.userId(),query,3);if(!longTerm.isBlank())query=query+"\n\n"+longTerm;return new QaRequest(request.datasetId(),query,request.conversationId(),request.userId(),request.userRoles());}
    private List<ToolDefinition> availableTools(QaRequest req){return tools.listEnabled().stream().filter(tool->canUse(tool,req)).toList();}
    private void requireToolAccess(ToolDefinition tool,QaRequest req){if(!canUse(tool,req))throw new IllegalStateException("用户无权调用工具: "+tool.name());}
    private boolean canUse(ToolDefinition tool,QaRequest req){boolean datasetOk=tool.allowedDatasetIds()==null||tool.allowedDatasetIds().isEmpty()||tool.allowedDatasetIds().contains(req.datasetId());if(!datasetOk)return false;Set<String> roles=req.userRoles()==null?Set.of():req.userRoles();return tool.allowedRoles()==null||tool.allowedRoles().isEmpty()||roles.stream().anyMatch(tool.allowedRoles()::contains);}
    private boolean queryIsHighRisk(String q){String value=q==null?"":q;return value.contains("删除")||value.contains("变更")||value.contains("提交审批")||value.contains("发起审批")||(value.contains("请审批")&&!value.contains("谁"));}
    private List<String> subtasks(String query){String[] parts=query.split("并且|然后|同时|以及|；|;");List<String> result=new ArrayList<>();for(String part:parts){String value=part.trim();if(!value.isBlank())result.add(value);}return result.isEmpty()?List.of(query):result.stream().limit(MAX_REACT_STEPS).toList();}
    private String combine(List<QaResult> results){if(results.isEmpty())return "Agent 未获得可用观察结果。";List<QaResult> usable=results.stream().filter(r->!r.refused()).toList();if(usable.isEmpty())return results.get(0).answer();if(usable.size()==1)return usable.get(0).answer();return usable.stream().map(QaResult::answer).map(answer->answer.replaceFirst("^结论[:：]","").trim()).collect(Collectors.joining("\n"));}
    private List<Citation> citations(List<QaResult> results){return results.stream().filter(result->!result.refused()).flatMap(result->result.citations().stream()).collect(Collectors.toMap(Citation::chunkId,citation->citation,(left,right)->left,java.util.LinkedHashMap::new)).values().stream().limit(5).toList();}
    private double confidence(List<QaResult> results){return results.stream().mapToDouble(QaResult::confidence).max().orElse(0);}
    private String lastTraceId(List<QaResult> results){for(int i=results.size()-1;i>=0;i--){String value=safeTraceId(results.get(i));if(!value.isBlank())return value;}return "";}
    private String toolOutput(QaResult result,ResilientToolExecutor.Result<QaResult> execution){return "{\"traceId\":\""+escape(result.traceId())+"\",\"refused\":"+result.refused()+",\"attempts\":"+execution.attempts()+",\"reused\":"+execution.reused()+",\"answer\":\""+escape(result.answer())+"\"}";}
    private String toolParams(QaRequest request,String subtask){return "{\"query\":\""+escape(subtask)+"\",\"datasetId\":"+request.datasetId()+",\"userId\":\""+escape(request.userId())+"\",\"conversationId\":\""+escape(request.conversationId()==null?"":String.valueOf(request.conversationId()))+"\"}";}
    private String observation(String subtask,QaResult result){return "query="+subtask+" refused="+result.refused()+" traceId="+safeTraceId(result)+" answer="+limit(result.answer(),180);}
    private String safeTraceId(QaResult result){return result.traceId()==null?"":result.traceId();}
    private String limit(String value,int max){String text=value==null?"":value;return text.length()<=max?text:text.substring(0,max)+"…";}
    private String datasetName(long datasetId){try{return store.dataset(datasetId).name();}catch(RuntimeException ignored){return null;}}
    private void rememberApprovalWait(QaRequest req,String approvalId){if(req.conversationId()==null)return;memory.append(req.conversationId(),"user",req.query());memory.append(req.conversationId(),"assistant","高风险请求已进入 Agent，等待审批后继续执行。审批号 "+shortId(approvalId),"[]",null,"agent",datasetName(req.datasetId()));}
    private void rememberApprovalDecision(QaRequest req,String answer,String traceId){if(req.conversationId()==null)return;memory.append(req.conversationId(),"assistant",answer,"[]",traceId,"agent",datasetName(req.datasetId()));}
    private void rememberAgentAnswer(QaRequest req,String answer,String traceId,List<Citation> citations,boolean rememberQuestion){if(req.conversationId()==null)return;if(rememberQuestion)memory.append(req.conversationId(),"user",req.query());memory.append(req.conversationId(),"assistant",answer,citationsJson(citations),traceId,"agent",datasetName(req.datasetId()));}
    private String citationsJson(List<Citation> citations){return citations.stream().map(c->"{\"chunkId\":"+c.chunkId()+",\"excerpt\":\""+escape(c.excerpt())+"\",\"score\":"+c.score()+"}").collect(Collectors.joining(",", "[", "]"));}
    private String shortId(String value){return value==null||value.length()<=8?String.valueOf(value):value.substring(0,8);}
    private String escape(String value){return (value==null?"":value).replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r");}
    private void publish(String id,String type,String message,Map<String,Object> data){stepTracer.record(id,type,message,data,0);sse.publish("agent:"+id,new SseEvent(type,message,data));}
}

