import React,{useEffect,useMemo,useRef,useState} from 'react';
import {Button,Card,Empty,Input,Modal,Space,Tag,Tooltip,Typography,message} from 'antd';
import {PlusOutlined} from '@ant-design/icons';
import {VariableSizeList,type ListChildComponentProps} from 'react-window';
import {agentApi,conversationApi,qaApi} from '../api/api';
import {useSSE,type StreamEvent} from '../hooks/useSSE';
import type {AutoQaResult,Conversation,Message} from '../types';
import {completedStatus,conversationTurnCount,formatLatency,patchMessageList,THINKING_TEXT} from './chatState';

type AgentStepView={id:string;type:string;message:string;status:'wait'|'process'|'finish'|'error'};

const stepLabel:Record<string,string>={
  THINK:'理解问题',PLAN:'生成计划',APPROVAL_REQUIRED:'等待审批',ACT:'执行工具',
  OBSERVE:'观察结果',ANSWER:'组织回答',THINKING:'理解问题',ROUTING:'自动路由',
  RETRIEVING:'检索知识库',GENERATING:'生成回答',AGENT_STARTED:'Agent 已启动',DONE:'完成',CANCELLED:'已取消',ERROR:'异常'
};

export function ChatPage({currentUserId}:{currentUserId:string}){
  const [history,setHistory]=useState<Conversation[]>([]);
  const [input,setInput]=useState('');
  const [pending,setPending]=useState<AutoQaResult>();
  const [approvalModalOpen,setApprovalModalOpen]=useState(false);
  const [pendingQuery,setPendingQuery]=useState('');
  const [busy,setBusy]=useState(false);
  const [executionId,setExecutionId]=useState<string>();
  const [conversationId,setConversationId]=useState<number>();
  const [messages,setMessages]=useState<Message[]>([]);
  const [clock,setClock]=useState(()=>Date.now());
  const [agentSteps,setAgentSteps]=useState<AgentStepView[]>([]);
  const [agentMessageId,setAgentMessageId]=useState<string>();
  const [autoStreamUrl,setAutoStreamUrl]=useState<string>();
  const [autoMessageId,setAutoMessageId]=useState<string>();
  const [autoQuery,setAutoQuery]=useState('');
  const agentStream=useSSE(executionId?`/api/v2/assistant/streams/${executionId}`:undefined);
  const autoStream=useSSE(autoStreamUrl?{url:'/api/v2/assistant/auto/stream',method:'POST',body:{question:autoQuery,conversationId}}:undefined);
  const latest=agentStream.events[agentStream.events.length-1];
  const autoLatest=autoStream.events[autoStream.events.length-1];
  const messageListRef=useRef<VariableSizeList<Message[]> | null>(null);

  const refreshHistory=async()=>setHistory(await conversationApi.list());
  useEffect(()=>{conversationApi.list().then(setHistory).catch(()=>undefined)},[]);

  const append=(next:Message)=>setMessages(current=>[...current,next]);
  const patchMessage=(id:string,update:Partial<Message>)=>setMessages(current=>patchMessageList(current,id,update));
  const ensureConversation=async(query:string)=>{if(conversationId)return conversationId;const created=await conversationApi.create(undefined,query.slice(0,80));setConversationId(created.id);await refreshHistory();return created.id};
  const newChat=()=>{setConversationId(undefined);setMessages([]);setExecutionId(undefined);setAgentSteps([]);setAgentMessageId(undefined);setPending(undefined);setApprovalModalOpen(false);setAutoStreamUrl(undefined);setAutoMessageId(undefined);setAutoQuery('');};
  const openConversation=async(conversation:Conversation)=>{
    setConversationId(conversation.id);setExecutionId(undefined);setAgentSteps([]);setAgentMessageId(undefined);setPending(undefined);setApprovalModalOpen(false);setAutoStreamUrl(undefined);setAutoMessageId(undefined);setAutoQuery('');
    const entries=await conversationApi.messages(conversation.id);
    const next=entries.map((entry,index)=>({id:`${conversation.id}-${entry.createdAt}-${index}`,role:entry.role,content:entry.content.replace(/\n*参考来源：(\s*\[\d+])+/,'').trim(),citations:parseCitations(entry.citations),mode:entry.mode,datasetName:entry.datasetName,traceId:entry.traceId,status:entry.role==='assistant'?'DONE':undefined} as Message));
    setMessages(next);
  };
  const archiveCurrent=async()=>{if(!conversationId)return;await conversationApi.archive(conversationId);newChat();await refreshHistory();message.success('聊天已归档，不再显示在用户历史列表中');};
  const submitFeedback=async(item:Message,rating:'LIKE'|'DISLIKE')=>{
    if(!item.traceId)return message.warning('历史消息暂无 Trace，无法反馈');
    try{await qaApi.feedback(item.traceId,rating);message.success(rating==='LIKE'?'已记录有用反馈':'已记录无用反馈，管理员可在审计页溯源')}
    catch(error){message.error(error instanceof Error?error.message:'反馈提交失败')}
  };

  const startAgentBubble=(result:AutoQaResult,query:string)=>{
    const messageId=crypto.randomUUID();
    setAgentMessageId(messageId);
    setAgentSteps(initialSteps(result.steps,result.status));
    const content=result.status==='WAITING_APPROVAL'?'高风险请求已进入 Agent，等待审批后继续执行。':result.answer||'Agent 正在执行。';
    append({id:messageId,role:'assistant',content,citations:result.citations,mode:'agent',steps:result.steps,datasetName:result.datasetName,traceId:result.traceId,status:completedStatus(result.status)});
    if(result.status==='WAITING_APPROVAL'){setPending(result);setPendingQuery(query);setApprovalModalOpen(true);}
  };

  const send=async()=>{
    if(!input.trim())return message.warning('请输入问题');
    const query=input.trim();append({id:crypto.randomUUID(),role:'user',content:query});setInput('');setBusy(true);setAgentSteps([]);setAgentMessageId(undefined);setPending(undefined);setApprovalModalOpen(false);setExecutionId(undefined);setAutoStreamUrl(undefined);
    try{
      const id=await ensureConversation(query);
      const messageId=crypto.randomUUID();
      setAutoMessageId(messageId);setAutoQuery(query);
      append({id:messageId,role:'assistant',content:THINKING_TEXT,mode:'rag',status:'THINKING',startedAt:Date.now()});
      setAutoStreamUrl('v2-auto');
    }catch(error){setBusy(false);message.error(error instanceof Error?error.message:'请求失败')}
  };

  const decide=async(approved:boolean)=>{
    if(!pending)return;
    setBusy(true);
    try{
      const result=await agentApi.approve(pending.approvalId!,pending.datasetId,pendingQuery,approved,conversationId);
      setExecutionId(result.executionId);setPending(undefined);
      setAgentSteps(current=>mergeSteps(current,initialSteps(result.steps,result.status)));
      if(agentMessageId)patchMessage(agentMessageId,{content:result.answer||result.status,steps:result.steps,citations:result.citations,traceId:result.traceId,status:completedStatus(result.status)});
      await refreshHistory();
    }catch(error){message.error(error instanceof Error?error.message:'审批处理失败')}
    finally{setBusy(false)}
  };

  const cancelAgent=async()=>{
    if(!executionId)return;
    try{
      await agentApi.cancel(executionId);
      setPending(undefined);setApprovalModalOpen(false);setBusy(false);
      message.success('已请求取消 Agent 执行');
    }catch(error){message.error(error instanceof Error?error.message:'取消失败')}
  };

  useEffect(()=>{
    if(!latest||!agentMessageId)return;
    setAgentSteps(current=>mergeSteps(current,[eventToStep(latest)]));
    if(latest.type==='DONE'||latest.type==='CANCELLED'||latest.type==='ERROR'){
      const data=latest.data||{};
      const content=latest.type==='ERROR'||latest.type==='CANCELLED'?latest.message:messages.find(item=>item.id===agentMessageId)?.content||'Agent 已完成';
      patchMessage(agentMessageId,{content,traceId:typeof data.traceId==='string'?data.traceId:undefined,status:latest.type==='DONE'?'DONE':latest.type==='ERROR'?'ERROR':undefined});
      refreshHistory().catch(()=>undefined);
    }
  },[latest]);

  useEffect(()=>{
    if(!autoLatest||!autoMessageId)return;
    if(autoLatest.type==='TOKEN'){
      const piece=typeof autoLatest.data?.text==='string'?autoLatest.data.text:autoLatest.message;
      setMessages(current=>current.map(item=>item.id===autoMessageId
        ?{...item,content:item.content===THINKING_TEXT||item.content==='回答已生成'?piece:item.content+piece,status:undefined}
        :item));
      return;
    }
    if(autoLatest.type==='AGENT_STARTED'){
      const id=typeof autoLatest.data?.executionId==='string'?autoLatest.data.executionId:undefined;
      if(id){setExecutionId(id);setAgentMessageId(autoMessageId);setAgentSteps(current=>mergeSteps(current,[eventToStep(autoLatest)]));}
      return;
    }
    if(autoLatest.type==='ANSWER')return;
    if(autoLatest.type==='PLAN'||autoLatest.type==='THINKING'||autoLatest.type==='ROUTING'){
      setMessages(current=>current.map(item=>item.id===autoMessageId
        ?{...item,content:THINKING_TEXT,status:'THINKING'}
        :item));
      return;
    }
    if(autoLatest.type!=='DONE'&&autoLatest.type!=='ERROR')return;
    if(autoLatest.type==='ERROR'){
      patchMessage(autoMessageId,{content:autoLatest.message,steps:['ERROR'],status:'ERROR'});
      setBusy(false);setAutoStreamUrl(undefined);return;
    }
    const result=autoLatest.data.result as AutoQaResult|undefined;
    if(!result){patchMessage(autoMessageId,{content:'自动问答返回格式异常',steps:['ERROR'],status:'ERROR'});setBusy(false);setAutoStreamUrl(undefined);return;}
    if(result.route==='AGENT'){
      setAgentMessageId(autoMessageId);
      setAgentSteps(initialSteps(result.steps,result.status));
      if(result.executionId)setExecutionId(result.executionId);
      if(result.status==='WAITING_APPROVAL'){setPending(result);setPendingQuery(autoQuery);setApprovalModalOpen(true);}
    }else{
      setAgentMessageId(undefined);setAgentSteps([]);setExecutionId(undefined);
    }
    const content=result.status==='WAITING_APPROVAL'?'高风险请求已进入 Agent，等待审批后继续执行。':result.answer||result.steps.join(' → ');
    patchMessage(autoMessageId,{content,citations:result.citations,mode:result.route==='AGENT'?'agent':'rag',steps:result.steps,datasetName:result.datasetName,traceId:result.traceId,status:completedStatus(result.status),latencyMs:elapsedFor(autoMessageId)});
    setBusy(false);setAutoStreamUrl(undefined);refreshHistory().catch(()=>undefined);
  },[autoLatest]);

  useEffect(()=>{
    if(!busy)return;
    const timer=window.setInterval(()=>setClock(Date.now()),100);
    return()=>window.clearInterval(timer);
  },[busy]);

  const activeTimeline=useMemo(()=>agentSteps.length?agentSteps:undefined,[agentSteps]);
  const messageHeights=useMemo(()=>messages.map(estimateMessageHeight),[messages]);
  useEffect(()=>{messageListRef.current?.resetAfterIndex(0,true);if(messages.length)messageListRef.current?.scrollToItem(messages.length-1,'end')},[messages.length,messageHeights]);
  const elapsedFor=(id:string)=>{
    const started=messages.find(item=>item.id===id)?.startedAt;
    return started?Math.max(0,Date.now()-started):undefined;
  };
  const renderMessage=(item:Message)=><article className={'bubble '+item.role}>
    <Tag color={item.role==='assistant'&&item.mode==='agent'?'orange':undefined}>{item.role==='user'?'你':item.mode==='agent'?'AGENT':'MODEL RAG'}</Tag>
    {item.datasetName&&<Tag>{item.datasetName}</Tag>}
    {item.status==='THINKING'?<div className="thinking-indicator"><span>{THINKING_TEXT} · 已等待 {formatLatency(Math.max(0,clock-(item.startedAt||clock)))}</span><i/><i/><i/></div>:<MarkdownContent text={item.content}/>}
    {item.mode==='agent'&&activeTimeline&&item.id===agentMessageId&&item.status!=='DONE'?<AgentStatusLine steps={activeTimeline}/>:null}
    {item.status==='DONE'&&<Space size={4} className="completion-status"><Tag color="green">DONE</Tag>{item.latencyMs!==undefined&&<Typography.Text type="secondary">耗时 {formatLatency(item.latencyMs)}</Typography.Text>}</Space>}
    {item.mode==='agent'&&pending&&item.id===agentMessageId&&<Space className="approval-inline"><Tag color="orange">等待审批</Tag><Typography.Text type="secondary">审批号 {pending.approvalId?.slice(0,8)}。当前账号 {currentUserId} 是发起人，请由非发起人在“审计与审批/审批待办”处理。</Typography.Text></Space>}
    {item.citations?.map(citation=><Tooltip key={citation.chunkId} title={<div className="citation-tooltip"><b>来源片段 #{citation.chunkId}</b><br/>{citation.excerpt}</div>}><Tag className="citation-trigger">[{citation.chunkId}] 来源</Tag></Tooltip>)}
    {item.role==='assistant'&&item.traceId&&<Space size="small" className="feedback-actions"><Button size="small" onClick={()=>submitFeedback(item,'LIKE')}>有用</Button><Button size="small" onClick={()=>submitFeedback(item,'DISLIKE')}>无用</Button><Typography.Text type="secondary">Trace {item.traceId.slice(0,8)}</Typography.Text></Space>}
  </article>;

  return <section className="chat">
    <div className="chat-grid">
      <Card className="conversation-list" size="small" title="历史聊天" extra={<Button size="small" icon={<PlusOutlined/>} onClick={newChat}>新聊天</Button>}>
        {conversationId&&<Button size="small" block onClick={archiveCurrent} className="archive-current">归档当前聊天</Button>}
        {history.length===0?<Typography.Text type="secondary">暂无历史聊天</Typography.Text>:history.map(item=><Button key={item.id} type={item.id===conversationId?'primary':'text'} block className="conversation-item" onClick={()=>openConversation(item)}><span>{item.title||'新会话'}</span><small>{conversationTurnCount(item.messageCount)} 轮</small></Button>)}
      </Card>
      <Card className="messages">
        {messages.length===0?<Empty description={null}/>:<VariableSizeList ref={messageListRef} className="message-virtual-list" height={620} width="100%" itemCount={messages.length} itemData={messages} itemSize={index=>messageHeights[index]||130} itemKey={(index,data)=>data[index].id} overscanCount={4}>{({index,style,data}:ListChildComponentProps<Message[]>)=><div style={style} className="virtual-message-row">{renderMessage(data[index])}</div>}</VariableSizeList>}
      </Card>
    </div>
    <Card className="composer"><Input.TextArea value={input} onChange={event=>setInput(event.target.value)} onPressEnter={event=>{if(!event.shiftKey){event.preventDefault();send()}}} placeholder="请输入问题，Shift + Enter 换行" autoSize={{minRows:4,maxRows:6}}/><div className="composer-footer"><Space><Button type="primary" loading={busy} onClick={send}>发送问题</Button>{executionId&&(busy||pending)&&<Button danger onClick={cancelAgent}>取消 Agent</Button>}</Space></div></Card>
    <Modal open={!!pending&&approvalModalOpen} title="已提交人工审批" footer={<Button type="primary" onClick={()=>setApprovalModalOpen(false)}>知道了</Button>} onCancel={()=>setApprovalModalOpen(false)}>
      <p>命中知识库：{pending?.datasetName}</p><p>执行 ID：{pending?.executionId}</p><p>高风险操作不会复用会话信任。当前账号是发起人，不能审批自己的请求；请由其他具有 APPROVER 权限的用户在“审计与审批/审批待办”页处理。</p>
    </Modal>
  </section>;
}

function AgentStatusLine({steps}:{steps:AgentStepView[]}){
  const latest=steps[steps.length-1];
  const text=latest?`${stepLabel[latest.type]||latest.type}：${latest.message}`:'Agent 准备执行';
  const path=steps.map(step=>stepLabel[step.type]||step.type).join(' → ');
  return <Tooltip title={path}><div className="agent-status-line"><Tag color={latest?.status==='error'?'red':latest?.status==='finish'?'green':'orange'}>{latest?.status==='finish'?'已完成':latest?.status==='error'?'异常':'进行中'}</Tag><span>{text}</span></div></Tooltip>;
}

function MarkdownContent({text}:{text:string}){
  const lines=(text||'').split(/\r?\n/);
  const nodes:React.ReactNode[]=[];
  let list:string[]=[];
  const flush=()=>{if(!list.length)return;nodes.push(<ul key={`ul-${nodes.length}`}>{list.map((item,index)=><li key={index}>{inlineMarkdown(item)}</li>)}</ul>);list=[];};
  lines.forEach((line,index)=>{
    const value=line.trim();
    if(!value){flush();return;}
    const heading=value.match(/^(#{1,3})\s+(.+)$/);
    if(heading){flush();const level=heading[1].length;nodes.push(level===1?<h3 key={index}>{inlineMarkdown(heading[2])}</h3>:level===2?<h4 key={index}>{inlineMarkdown(heading[2])}</h4>:<h5 key={index}>{inlineMarkdown(heading[2])}</h5>);return;}
    const bullet=value.match(/^[-*]\s+(.+)$/);
    if(bullet){list.push(bullet[1]);return;}
    flush();nodes.push(<p key={index}>{inlineMarkdown(value)}</p>);
  });
  flush();
  return <div className="markdown-answer">{nodes}</div>;
}

function inlineMarkdown(value:string){
  const parts=value.split(/(`[^`]+`|\*\*[^*]+\*\*)/g).filter(Boolean);
  return parts.map((part,index)=>{
    if(part.startsWith('`')&&part.endsWith('`'))return <code key={index}>{part.slice(1,-1)}</code>;
    if(part.startsWith('**')&&part.endsWith('**'))return <strong key={index}>{part.slice(2,-2)}</strong>;
    return <React.Fragment key={index}>{part}</React.Fragment>;
  });
}

function initialSteps(values:string[]|undefined,status:string){
  const list=(values&&values.length?values:[status]).map((value,index):AgentStepView=>({id:`initial-${index}-${value}`,type:value.replace(/^ACT:.+$/,'ACT'),message:value,status:index===(values?.length||1)-1&&status!=='DONE'?'process':'finish'}));
  return list;
}

function eventToStep(event:StreamEvent):AgentStepView{
  return {id:`event-${Date.now()}-${event.type}-${event.message}`,type:event.type,message:event.message,status:event.type==='ERROR'?'error':event.type==='DONE'||event.type==='CANCELLED'?'finish':'process'};
}

function mergeSteps(current:AgentStepView[],incoming:AgentStepView[]){
  const merged=[...current];
  for(const step of incoming){
    const last=merged[merged.length-1];
    if(last&&last.type===step.type&&last.message===step.message)continue;
    if(last&&last.status==='process')last.status='finish';
    merged.push(step);
  }
  return merged.slice(-12);
}

function parseCitations(value:string|undefined){
  if(!value)return [];
  try{return JSON.parse(value)}
  catch{return []}
}

function estimateMessageHeight(item:Message){
  const lines=Math.ceil((item.content?.length||0)/42);
  const citationRows=Math.ceil((item.citations?.length||0)/3);
  const stepExtra=item.steps?.length?28:0;
  const agentExtra=item.mode==='agent'?36:0;
  return Math.max(96,Math.min(360,86+lines*22+citationRows*28+stepExtra+agentExtra));
}
