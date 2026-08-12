import React,{useEffect,useMemo,useRef,useState} from 'react';
import {Button,Card,Empty,Input,Modal,Space,Tag,Tooltip,Typography,message} from 'antd';
import {PlusOutlined} from '@ant-design/icons';
import {VariableSizeList,type ListChildComponentProps} from 'react-window';
import {agentApi,conversationApi,qaApi} from '../api/api';
import {useSSE,type StreamEvent} from '../hooks/useSSE';
import type {AutoQaResult,Conversation,Message} from '../types';

type AgentStepView={id:string;type:string;message:string;status:'wait'|'process'|'finish'|'error'};

const stepLabel:Record<string,string>={
  THINK:'理解问题',PLAN:'生成计划',APPROVAL_REQUIRED:'等待审批',ACT:'执行工具',
  OBSERVE:'观察结果',ANSWER:'组织回答',THINKING:'理解问题',ROUTING:'自动路由',
  RETRIEVING:'检索知识库',GENERATING:'生成回答',DONE:'完成',ERROR:'异常'
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
  const [lastRoute,setLastRoute]=useState<string>();
  const [lastDataset,setLastDataset]=useState<string>();
  const [lastOutputTokens,setLastOutputTokens]=useState(0);
  const [agentSteps,setAgentSteps]=useState<AgentStepView[]>([]);
  const [agentMessageId,setAgentMessageId]=useState<string>();
  const [autoStreamUrl,setAutoStreamUrl]=useState<string>();
  const [autoMessageId,setAutoMessageId]=useState<string>();
  const [autoQuery,setAutoQuery]=useState('');
  const agentStream=useSSE(executionId?`/api/v1/qa/agent/${executionId}/stream`:undefined);
  const autoStream=useSSE(autoStreamUrl);
  const latest=agentStream.events[agentStream.events.length-1];
  const autoLatest=autoStream.events[autoStream.events.length-1];
  const messageListRef=useRef<VariableSizeList<Message[]> | null>(null);

  const refreshHistory=async()=>setHistory(await conversationApi.list());
  useEffect(()=>{conversationApi.list().then(setHistory).catch(()=>undefined)},[]);

  const append=(next:Message)=>setMessages(current=>[...current,next]);
  const patchMessage=(id:string,update:Partial<Message>)=>setMessages(current=>current.map(item=>item.id===id?{...item,...update}:item));
  const ensureConversation=async(query:string)=>{if(conversationId)return conversationId;const created=await conversationApi.create(undefined,query.slice(0,80));setConversationId(created.id);await refreshHistory();return created.id};
  const newChat=()=>{setConversationId(undefined);setMessages([]);setExecutionId(undefined);setLastRoute(undefined);setLastDataset(undefined);setLastOutputTokens(0);setAgentSteps([]);setAgentMessageId(undefined);setPending(undefined);setApprovalModalOpen(false);setAutoStreamUrl(undefined);setAutoMessageId(undefined);setAutoQuery('');};
  const openConversation=async(conversation:Conversation)=>{
    setConversationId(conversation.id);setExecutionId(undefined);setLastRoute(undefined);setLastDataset(undefined);setAgentSteps([]);setAgentMessageId(undefined);setPending(undefined);setApprovalModalOpen(false);setAutoStreamUrl(undefined);setAutoMessageId(undefined);setAutoQuery('');
    const entries=await conversationApi.messages(conversation.id);
    const next=entries.map((entry,index)=>({id:`${conversation.id}-${entry.createdAt}-${index}`,role:entry.role,content:entry.content.replace(/\n*参考来源：(\s*\[\d+])+/,'').trim(),citations:parseCitations(entry.citations),mode:entry.mode,datasetName:entry.datasetName,traceId:entry.traceId} as Message));
    setMessages(next);
    setLastOutputTokens(estimateTokens([...next].reverse().find(item=>item.role==='assistant')?.content||''));
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
    append({id:messageId,role:'assistant',content,citations:result.citations,mode:'agent',steps:result.steps,datasetName:result.datasetName,traceId:result.traceId});
    setLastOutputTokens(estimateTokens(content));
    if(result.status==='WAITING_APPROVAL'){setPending(result);setPendingQuery(query);setApprovalModalOpen(true);}
  };

  const send=async()=>{
    if(!input.trim())return message.warning('请输入问题');
    const query=input.trim();append({id:crypto.randomUUID(),role:'user',content:query});setInput('');setBusy(true);setAgentSteps([]);setAgentMessageId(undefined);setPending(undefined);setApprovalModalOpen(false);setExecutionId(undefined);setAutoStreamUrl(undefined);
    try{
      const id=await ensureConversation(query);
      const messageId=crypto.randomUUID();
      setAutoMessageId(messageId);setAutoQuery(query);
      append({id:messageId,role:'assistant',content:'正在自动选择知识库和执行模式。',mode:'rag',steps:['THINKING']});
      setAutoStreamUrl(`/api/v1/qa/auto/stream?q=${encodeURIComponent(query)}&convId=${id}`);
    }catch(error){setBusy(false);message.error(error instanceof Error?error.message:'请求失败')}
  };

  const decide=async(approved:boolean)=>{
    if(!pending)return;
    setBusy(true);
    try{
      const result=await agentApi.approve(pending.approvalId!,pending.datasetId,pendingQuery,approved,conversationId);
      setExecutionId(result.executionId);setLastRoute(result.route);setPending(undefined);
      setAgentSteps(current=>mergeSteps(current,initialSteps(result.steps,result.status)));
      if(agentMessageId)patchMessage(agentMessageId,{content:result.answer||result.status,steps:result.steps,citations:result.citations,traceId:result.traceId});
      setLastOutputTokens(estimateTokens(result.answer||result.status));
      await refreshHistory();
    }catch(error){message.error(error instanceof Error?error.message:'审批处理失败')}
    finally{setBusy(false)}
  };

  useEffect(()=>{
    if(!latest||!agentMessageId)return;
    setAgentSteps(current=>mergeSteps(current,[eventToStep(latest)]));
    if(latest.type==='DONE'||latest.type==='ERROR'){
      const data=latest.data||{};
      const content=latest.type==='ERROR'?latest.message:messages.find(item=>item.id===agentMessageId)?.content||'Agent 已完成';
      patchMessage(agentMessageId,{content,traceId:typeof data.traceId==='string'?data.traceId:undefined});
      setLastOutputTokens(estimateTokens(content));
      refreshHistory().catch(()=>undefined);
    }
  },[agentStream.events.length]);

  useEffect(()=>{
    if(!autoLatest||!autoMessageId)return;
    if(autoLatest.type!=='DONE'&&autoLatest.type!=='ERROR'){
      patchMessage(autoMessageId,{content:autoLatest.message,steps:[...(messages.find(item=>item.id===autoMessageId)?.steps||[]),autoLatest.type].slice(-6)});
      return;
    }
    if(autoLatest.type==='ERROR'){
      patchMessage(autoMessageId,{content:autoLatest.message,steps:['ERROR']});
      setBusy(false);setAutoStreamUrl(undefined);return;
    }
    const result=autoLatest.data.result as AutoQaResult|undefined;
    if(!result){patchMessage(autoMessageId,{content:'自动问答返回格式异常',steps:['ERROR']});setBusy(false);setAutoStreamUrl(undefined);return;}
    setLastRoute(result.route);setLastDataset(result.datasetName);
    if(result.route==='AGENT'){
      setAgentMessageId(autoMessageId);
      setAgentSteps(initialSteps(result.steps,result.status));
      if(result.executionId)setExecutionId(result.executionId);
      if(result.status==='WAITING_APPROVAL'){setPending(result);setPendingQuery(autoQuery);setApprovalModalOpen(true);}
    }else{
      setAgentMessageId(undefined);setAgentSteps([]);setExecutionId(undefined);
    }
    const content=result.status==='WAITING_APPROVAL'?'高风险请求已进入 Agent，等待审批后继续执行。':result.answer||result.steps.join(' → ');
    patchMessage(autoMessageId,{content,citations:result.citations,mode:result.route==='AGENT'?'agent':'rag',steps:result.steps,datasetName:result.datasetName,traceId:result.traceId});
    setLastOutputTokens(estimateTokens(content));
    setBusy(false);setAutoStreamUrl(undefined);refreshHistory().catch(()=>undefined);
  },[autoStream.events.length]);

  const activeTimeline=useMemo(()=>agentSteps.length?agentSteps:undefined,[agentSteps]);
  const messageHeights=useMemo(()=>messages.map(estimateMessageHeight),[messages]);
  useEffect(()=>{messageListRef.current?.resetAfterIndex(0,true);if(messages.length)messageListRef.current?.scrollToItem(messages.length-1,'end')},[messages.length,messageHeights]);
  const renderMessage=(item:Message)=><article className={'bubble '+item.role}>
    <Tag color={item.role==='assistant'&&item.mode==='agent'?'orange':undefined}>{item.role==='user'?'你':item.mode==='agent'?'AGENT':'MODEL RAG'}</Tag>
    {item.datasetName&&<Tag>{item.datasetName}</Tag>}
    <MarkdownContent text={item.content}/>
    {item.mode==='agent'&&activeTimeline&&item.id===agentMessageId?<AgentStatusLine steps={activeTimeline}/>:item.steps&&<div className="agent-steps">实际路径：{item.steps.join(' → ')}</div>}
    {item.mode==='agent'&&pending&&item.id===agentMessageId&&<Space className="approval-inline"><Tag color="orange">等待审批</Tag><Typography.Text type="secondary">审批号 {pending.approvalId?.slice(0,8)}。当前账号 {currentUserId} 是发起人，请由非发起人在“审计与审批/审批待办”处理。</Typography.Text></Space>}
    {item.citations?.map(citation=><Tooltip key={citation.chunkId} title={<div className="citation-tooltip"><b>来源片段 #{citation.chunkId}</b><br/>{citation.excerpt}</div>}><Tag className="citation-trigger">[{citation.chunkId}] 来源</Tag></Tooltip>)}
    {item.role==='assistant'&&item.traceId&&<Space size="small" className="feedback-actions"><Button size="small" onClick={()=>submitFeedback(item,'LIKE')}>有用</Button><Button size="small" onClick={()=>submitFeedback(item,'DISLIKE')}>无用</Button><Typography.Text type="secondary">Trace {item.traceId.slice(0,8)}</Typography.Text></Space>}
  </article>;

  return <section className="chat">
    <div className="eyebrow">RAG · AGENT CONSOLE</div>
    <h1>问一个有证据的问题。</h1>
    <div className="chat-grid">
      <Card className="conversation-list" size="small" title="历史聊天" extra={<Button size="small" icon={<PlusOutlined/>} onClick={newChat}>新聊天</Button>}>
        {conversationId&&<Button size="small" block onClick={archiveCurrent} className="archive-current">归档当前聊天</Button>}
        {history.length===0?<Typography.Text type="secondary">暂无历史聊天</Typography.Text>:history.map(item=><Button key={item.id} type={item.id===conversationId?'primary':'text'} block className="conversation-item" onClick={()=>openConversation(item)}><span>{item.title||'新会话'}</span><small>{item.messageCount} 条</small></Button>)}
      </Card>
      <Card className="messages">
        {messages.length===0?<Empty description="系统会自动识别知识库，并判断走 RAG 还是 Agent"/>:<VariableSizeList ref={messageListRef} className="message-virtual-list" height={520} width="100%" itemCount={messages.length} itemData={messages} itemSize={index=>messageHeights[index]||130} itemKey={(index,data)=>data[index].id} overscanCount={4}>{({index,style,data}:ListChildComponentProps<Message[]>)=><div style={style} className="virtual-message-row">{renderMessage(data[index])}</div>}</VariableSizeList>}
      </Card>
    </div>
    <Card className="composer"><Input.TextArea value={input} onChange={event=>setInput(event.target.value)} onPressEnter={event=>{if(!event.shiftKey){event.preventDefault();send()}}} placeholder="请输入问题，Shift + Enter 换行" autoSize={{minRows:3,maxRows:5}}/><div className="composer-footer"><Button type="primary" loading={busy} onClick={send}>发送问题</Button><Typography.Text type="secondary">{lastRoute?`${lastRoute}${lastDataset?` · ${lastDataset}`:''} · `:''}上次助手输出约 {lastOutputTokens} tokens</Typography.Text></div></Card>
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
  return {id:`event-${Date.now()}-${event.type}-${event.message}`,type:event.type,message:event.message,status:event.type==='ERROR'?'error':event.type==='DONE'?'finish':'process'};
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

function estimateTokens(value:string){
  return Math.max(0,Math.ceil((value||'').length/2));
}
