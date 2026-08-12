import React,{useEffect,useState} from 'react';
import {Alert,Button,Card,Descriptions,Modal,Space,Table,Tag,Typography,message} from 'antd';
import {ReloadOutlined} from '@ant-design/icons';
import {adminApi,agentApi,evalApi} from '../api/api';
import type {AgentStepTrace,Approval,Feedback,QaAudit,RetrievalReplay,ToolTrace} from '../types';

const time=(value:string)=>new Date(value).toLocaleString('zh-CN',{hour12:false});
const jsonText=(value?:string)=>value&&value!=='[]'?value:'[]';

export function AuditPage({admin,currentUserId,onOpenEval}:{admin:boolean;currentUserId:string;onOpenEval?:(datasetId:number)=>void}){
  const [audits,setAudits]=useState<QaAudit[]>([]);
  const [approvals,setApprovals]=useState<Approval[]>([]);
  const [feedbacks,setFeedbacks]=useState<Feedback[]>([]);
  const [toolTraces,setToolTraces]=useState<ToolTrace[]>([]);
  const [agentSteps,setAgentSteps]=useState<AgentStepTrace[]>([]);
  const [loading,setLoading]=useState(false);
  const [replay,setReplay]=useState<RetrievalReplay>();
  const [replayLoading,setReplayLoading]=useState(false);
  const [importing,setImporting]=useState(false);
  const [approving,setApproving]=useState<string>();

  const load=async()=>{
    setLoading(true);
    try{
      if(!admin){
        setApprovals(await agentApi.approvals());
        setAudits([]);
        setFeedbacks([]);
        setToolTraces([]);
        setAgentSteps([]);
        return;
      }
      const [questions,pending,feedbackRows,tools,steps]=await Promise.all([adminApi.qaAudits(),adminApi.approvals(),adminApi.feedbacks(),adminApi.toolTraces(),adminApi.agentSteps()]);
      setAudits(questions);
      setApprovals(pending);
      setFeedbacks(feedbackRows);
      setToolTraces(tools);
      setAgentSteps(steps);
    }catch(error){
      message.error(error instanceof Error?error.message:'审计数据加载失败');
    }finally{
      setLoading(false);
    }
  };
  const openReplay=async(traceId:string)=>{
    setReplayLoading(true);
    try{
      const data=await adminApi.replay(traceId);
      setReplay(data);
      if(!data.found)message.warning('没有找到该 trace 的检索记录');
    }catch(error){
      message.error(error instanceof Error?error.message:'Trace 回放失败');
    }finally{
      setReplayLoading(false);
    }
  };
  const importReplay=async()=>{
    if(!replay?.traceId)return;
    setImporting(true);
    try{
      const id=await evalApi.fromTrace(replay.traceId);
      message.success(`已加入评测集 #${id}`);
      if(replay.datasetId)onOpenEval?.(replay.datasetId);
    }catch(error){
      message.error(error instanceof Error?error.message:'加入评测集失败');
    }finally{
      setImporting(false);
    }
  };
  const importTrace=async(traceId:string,datasetId?:number)=>{
    setImporting(true);
    try{
      const id=await evalApi.fromTrace(traceId);
      message.success(`已加入评测集 #${id}`);
      if(datasetId)onOpenEval?.(datasetId);
    }catch(error){
      message.error(error instanceof Error?error.message:'加入评测集失败');
    }finally{
      setImporting(false);
    }
  };
  const approvalQuery=(record:Approval)=>{
    try{
      const parsed=JSON.parse(record.params||'{}') as {query?:string};
      return parsed.query||'';
    }catch{
      return '';
    }
  };
  const decideApproval=async(record:Approval,approved:boolean)=>{
    if(record.requesterUserId===currentUserId)return message.warning('发起人不能审批自己的请求，请由其他 APPROVER 处理');
    const query=approvalQuery(record);
    if(!record.datasetId)return message.warning('审批记录缺少知识库 ID，无法处理');
    if(!query)return message.warning('审批记录缺少原始问题，无法处理');
    setApproving(record.id);
    try{
      const result=await agentApi.approve(record.id,record.datasetId,query,approved,record.conversationId);
      message.success(approved?`已批准并继续执行：${result.status}`:'已拒绝该高风险请求');
      await load();
    }catch(error){
      message.error(error instanceof Error?error.message:'审批处理失败');
    }finally{
      setApproving(undefined);
    }
  };
  useEffect(()=>{load();const timer=window.setInterval(load,10000);return()=>window.clearInterval(timer)},[]);

  return <section className="audit-page">
    <div className="eyebrow">ADMIN / TRACEABILITY</div>
    <h1>{admin?'审计与审批':'审批待办'}</h1>
    <Alert showIcon type="info" message={admin?'当前后端已接入基于请求头的角色与知识库访问控制；生产部署仍建议接入统一登录、租户和网关鉴权。':'这里展示当前审批人有权处理的高风险 Agent 请求；审批发起人不能审批自己的请求。'}/>
    <Space className="audit-toolbar"><span>每 10 秒刷新一次</span><Button icon={<ReloadOutlined/>} loading={loading} onClick={load}>立即刷新</Button></Space>
    <div className="audit-grid">
      <Card size="small" title={`待办与审批（${approvals.filter(item=>item.status==='PENDING').length}）`}>
        <Table size="small" rowKey="id" dataSource={approvals} pagination={{pageSize:6,size:'small'}} scroll={{y:260}} columns={[
          {title:'状态',dataIndex:'status',width:100,render:value=><Tag color={value==='PENDING'?'gold':value==='APPROVED'?'green':'default'}>{value}</Tag>},
          {title:'风险工具',dataIndex:'toolName'},
          {title:'发起范围',width:170,render:(_,record:Approval)=><Space direction="vertical" size={0}><span>用户：{record.requesterUserId||'未知'}</span><span>知识库：{record.datasetId?`#${record.datasetId}`:'-'}</span><span>会话：{record.conversationId?`#${record.conversationId}`:'-'}</span></Space>},
          {title:'请求参数',dataIndex:'params',ellipsis:true},
          {title:'审批人',dataIndex:'approvedBy',width:110,render:value=>value||'未处理'},
          {title:'截止',dataIndex:'expiresAt',width:155,render:value=>time(value)},
          {title:'操作',width:170,render:(_,record:Approval)=>record.status==='PENDING'?(record.requesterUserId===currentUserId?<Tag color="default">不能自审</Tag>:<Space><Button size="small" type="primary" loading={approving===record.id} onClick={()=>decideApproval(record,true)}>批准</Button><Button size="small" danger loading={approving===record.id} onClick={()=>decideApproval(record,false)}>拒绝</Button></Space>):<Tag>已处理</Tag>}
        ]}/>
        <Typography.Paragraph type="secondary">当前会话的 SSE 审批事件会推送给发起该 Agent 执行的客户端；所有记录也会保存在此处供管理员查看。高风险定义为工具风险级别 HIGH（当前内置“删除 / 变更 / 审批”意图）。</Typography.Paragraph>
      </Card>
      {admin&&<>
      <Card size="small" title={`问答留痕（${audits.length}）`}>
        <Table size="small" rowKey={(item:QaAudit)=>`${item.traceId}-${item.createdAt}`} dataSource={audits} pagination={{pageSize:6,size:'small'}} scroll={{y:320}} columns={[
          {title:'时间',dataIndex:'createdAt',width:155,render:value=>time(value)},
          {title:'知识库',dataIndex:'datasetName',width:130,render:(value,record)=>value||`#${record.datasetId}`},
          {title:'模式',dataIndex:'mode',width:92,render:value=><Tag color={value==='agent'?'orange':'green'}>{value==='agent'?'Agent':'RAG'}</Tag>},
          {title:'提问用户',dataIndex:'userId',width:120,render:value=>value||'未知'},
          {title:'问题与回答',render:(_,record:QaAudit)=><><Typography.Paragraph ellipsis={{rows:1,expandable:true,symbol:'展开问题'}}><b>问：</b>{record.query}</Typography.Paragraph><Typography.Paragraph ellipsis={{rows:2,expandable:true,symbol:'展开回答'}}><b>答：</b>{record.answer}</Typography.Paragraph></>},
          {title:'结果',width:84,render:(_,record:QaAudit)=><Tag color={record.refused?'red':'green'}>{record.refused?'拒答':'已回答'}</Tag>},
          {title:'Trace',dataIndex:'traceId',width:220,render:(value,record:QaAudit)=><Space><Typography.Text copyable={{text:value}} ellipsis style={{maxWidth:60}}>{value}</Typography.Text><Button size="small" loading={replayLoading} onClick={()=>openReplay(value)}>回放</Button><Button size="small" loading={importing} onClick={()=>importTrace(value,record.datasetId)}>入集</Button></Space>}
        ]}/>
      </Card>
      <Card size="small" title={`用户反馈（${feedbacks.length}）`}>
        <Table size="small" rowKey="id" dataSource={feedbacks} pagination={{pageSize:6,size:'small'}} scroll={{y:260}} columns={[
          {title:'时间',dataIndex:'createdAt',width:155,render:value=>time(value)},
          {title:'结果',dataIndex:'rating',width:90,render:value=><Tag color={value==='DISLIKE'?'red':'green'}>{value==='DISLIKE'?'无用':'有用'}</Tag>},
          {title:'用户',dataIndex:'userId',width:120,render:value=>value||'未知'},
          {title:'知识库',dataIndex:'datasetId',width:90,render:value=>`#${value}`},
          {title:'备注',dataIndex:'comment',ellipsis:true,render:value=>value||'无'},
          {title:'Trace',dataIndex:'traceId',width:150,render:value=><Space><Typography.Text copyable={{text:value}} ellipsis style={{maxWidth:60}}>{value}</Typography.Text><Button size="small" loading={replayLoading} onClick={()=>openReplay(value)}>回放</Button></Space>}
        ]}/>
        <Typography.Paragraph type="secondary">DISLIKE 反馈应优先回放 Trace，再按召回、排序、上下文或生成问题归类并加入评测集。</Typography.Paragraph>
      </Card>
      </>}
    </div>
    {admin&&<>
    <Card size="small" title={`工具调用留痕（${toolTraces.length}）`} className="monitor">
      <Table size="small" rowKey={(item:ToolTrace,index)=>`${item.traceId}-${item.toolName}-${index}`} dataSource={toolTraces} pagination={{pageSize:6,size:'small'}} scroll={{y:300}} columns={[
        {title:'状态',dataIndex:'success',width:86,render:value=><Tag color={value?'green':'red'}>{value?'成功':'失败'}</Tag>},
        {title:'工具',dataIndex:'toolName',width:150},
        {title:'耗时',dataIndex:'latencyMs',width:90,render:value=>`${value} ms`},
        {title:'输入',dataIndex:'params',render:value=><Typography.Paragraph copyable={{text:value}} ellipsis={{rows:1,expandable:true,symbol:'展开输入'}}>{value}</Typography.Paragraph>},
        {title:'输出 / 错误',render:(_,record:ToolTrace)=><Typography.Paragraph copyable={{text:record.success?record.output:record.error||''}} ellipsis={{rows:2,expandable:true,symbol:'展开详情'}}>{record.success?record.output:record.error}</Typography.Paragraph>},
        {title:'Trace',dataIndex:'traceId',width:160,render:value=><Typography.Text copyable={{text:value}} ellipsis style={{maxWidth:110}}>{value}</Typography.Text>}
      ]}/>
      <Typography.Paragraph type="secondary">工具 trace 用于定位 Agent 阶段问题：成功时看 output_result，失败时看 error_msg；可与问答 Trace ID 关联回放检索链路。</Typography.Paragraph>
    </Card>
    <Card size="small" title={`Agent 执行步骤留痕（${agentSteps.length}）`} className="monitor">
      <Table size="small" rowKey={(item:AgentStepTrace,index)=>`${item.executionId}-${item.stepIndex}-${index}`} dataSource={agentSteps} pagination={{pageSize:8,size:'small'}} scroll={{y:360}} columns={[
        {title:'时间',dataIndex:'createdAt',width:155,render:value=>time(value)},
        {title:'执行 ID',dataIndex:'executionId',width:150,render:value=><Typography.Text copyable={{text:value}} ellipsis style={{maxWidth:100}}>{value}</Typography.Text>},
        {title:'序号',dataIndex:'stepIndex',width:62},
        {title:'阶段',dataIndex:'phase',width:120,render:value=><Tag color={value==='ERROR'?'red':value==='DONE'?'green':value==='APPROVAL_REQUIRED'?'gold':'blue'}>{value}</Tag>},
        {title:'状态',dataIndex:'status',width:90,render:value=><Tag>{value}</Tag>},
        {title:'消息',dataIndex:'message',ellipsis:true},
        {title:'结构化数据',dataIndex:'data',render:value=><Typography.Paragraph copyable={{text:value}} ellipsis={{rows:1,expandable:true,symbol:'展开数据'}}>{value}</Typography.Paragraph>}
      ]}/>
      <Typography.Paragraph type="secondary">Agent step trace 用于回放 ReAct 链路：THINK / PLAN / ACT / OBSERVE / ANSWER / APPROVAL / ERROR / DONE 都会留下结构化数据。</Typography.Paragraph>
    </Card>
    </>}
    <Modal open={!!replay} title={`检索回放 ${replay?.traceId||''}`} width={920} footer={<Space><Button disabled={!replay?.found} loading={importing} onClick={importReplay}>加入评测集</Button><Button onClick={()=>setReplay(undefined)}>关闭</Button></Space>} onCancel={()=>setReplay(undefined)}>
      <Descriptions size="small" bordered column={2}>
        <Descriptions.Item label="知识库">{replay?.datasetId}</Descriptions.Item>
        <Descriptions.Item label="耗时">{replay?.latencyMs||0} ms</Descriptions.Item>
        <Descriptions.Item label="置信度">{replay?.confidence}</Descriptions.Item>
        <Descriptions.Item label="结果">{replay?.refused?<Tag color="red">拒答</Tag>:<Tag color="green">已回答</Tag>}</Descriptions.Item>
        <Descriptions.Item label="阶段计数" span={2}>{Object.entries(replay?.stageCounts||{}).map(([key,value])=><Tag key={key}>{key}:{value}</Tag>)}</Descriptions.Item>
        <Descriptions.Item label="问题阶段" span={2}><Tag color={replay?.failureStage==='ANSWER_USE'?'green':'red'}>{replay?.failureStage||'UNKNOWN'}</Tag></Descriptions.Item>
        <Descriptions.Item label="答案来源"><Tag color={replay?.answerSource==='llm'?'blue':replay?.answerSource==='refusal'?'red':'green'}>{replay?.answerSource||'UNKNOWN'}</Tag></Descriptions.Item>
        <Descriptions.Item label="上下文限制">{replay?.contextMaxTokens||0} tokens</Descriptions.Item>
        <Descriptions.Item label="诊断" span={2}>{(replay?.diagnosis||[]).map(item=><p key={item}>{item}</p>)}</Descriptions.Item>
        <Descriptions.Item label="处理建议" span={2}>{(replay?.actionHints||[]).map(item=><p key={item}>{item}</p>)}</Descriptions.Item>
      </Descriptions>
      <Card size="small" title="Query / Rewrite / Answer" className="monitor">
        <Typography.Paragraph><b>Query：</b>{replay?.query}</Typography.Paragraph>
        <Typography.Paragraph><b>改写：</b>{replay?.rewrittenQuery||'无'}</Typography.Paragraph>
        <Typography.Paragraph><b>多路检索：</b>{jsonText(replay?.searchQueries)}</Typography.Paragraph>
        <Typography.Paragraph><b>Rerank Query：</b>{replay?.rerankQuery||'无'}</Typography.Paragraph>
        <Typography.Paragraph><b>Answer：</b>{replay?.answer||'无'}</Typography.Paragraph>
      </Card>
      <Card size="small" title="生成审计：Prompt / 上下文 / 模型原始输出" className="monitor">
        <Typography.Paragraph type="secondary">用于判断 bad case 是上下文拼接问题、Prompt 约束问题，还是模型没有正确使用证据。</Typography.Paragraph>
        <Typography.Paragraph copyable={{text:replay?.finalPrompt||''}} ellipsis={{rows:4,expandable:true,symbol:'展开最终 Prompt'}}>最终 Prompt：{replay?.finalPrompt||'无'}</Typography.Paragraph>
        <Typography.Paragraph copyable={{text:replay?.promptContext||''}} ellipsis={{rows:4,expandable:true,symbol:'展开 Prompt 上下文'}}>Prompt 上下文：{replay?.promptContext||'无'}</Typography.Paragraph>
        <Typography.Paragraph copyable={{text:replay?.modelOutput||''}} ellipsis={{rows:3,expandable:true,symbol:'展开模型原始输出'}}>模型原始输出：{replay?.modelOutput||'无'}</Typography.Paragraph>
      </Card>
      <Card size="small" title="最终证据摘要" className="monitor">
        {(replay?.evidencePreview||[]).length===0?<Typography.Text type="secondary">无最终上下文证据</Typography.Text>:(replay?.evidencePreview||[]).map(item=><Card key={`${item.chunkId}-${item.rank}`} size="small" style={{marginBottom:8}}><Space wrap><Tag>#{item.chunkId}</Tag><Tag>rank {item.rank}</Tag><Tag>{item.channel||'context'}</Tag><Tag>{Number(item.score||0).toFixed(3)}</Tag></Space><Typography.Paragraph style={{marginBottom:0,whiteSpace:'pre-wrap'}}>{item.excerpt}</Typography.Paragraph></Card>)}
      </Card>
      <Card size="small" title="向量 Top-N / BM25 Top-N / RRF / Rerank / MMR / Small-to-Big / A/B / 最终上下文" className="monitor">
        <Typography.Paragraph copyable={{text:jsonText(replay?.vectorResults)}} ellipsis={{rows:2,expandable:true,symbol:'展开向量召回'}}>向量：{jsonText(replay?.vectorResults)}</Typography.Paragraph>
        <Typography.Paragraph copyable={{text:jsonText(replay?.bm25Results)}} ellipsis={{rows:2,expandable:true,symbol:'展开 BM25'}}>BM25：{jsonText(replay?.bm25Results)}</Typography.Paragraph>
        <Typography.Paragraph copyable={{text:jsonText(replay?.fusedResults)}} ellipsis={{rows:2,expandable:true,symbol:'展开 RRF'}}>RRF：{jsonText(replay?.fusedResults)}</Typography.Paragraph>
        <Typography.Paragraph copyable={{text:jsonText(replay?.rerankResults)}} ellipsis={{rows:2,expandable:true,symbol:'展开 Rerank'}}>Rerank：{jsonText(replay?.rerankResults)}</Typography.Paragraph>
        <Typography.Paragraph copyable={{text:jsonText(replay?.mmrResults)}} ellipsis={{rows:2,expandable:true,symbol:'展开 MMR'}}>MMR：{jsonText(replay?.mmrResults)}</Typography.Paragraph>
        <Typography.Paragraph copyable={{text:jsonText(replay?.smallToBigContext)}} ellipsis={{rows:3,expandable:true,symbol:'展开 Small-to-Big'}}>Small-to-Big：{jsonText(replay?.smallToBigContext)}</Typography.Paragraph>
        <Typography.Paragraph copyable={{text:jsonText(replay?.abVariants)}} ellipsis={{rows:2,expandable:true,symbol:'展开 A/B 影子变体'}}>A/B 影子变体：{jsonText(replay?.abVariants)}</Typography.Paragraph>
        <Typography.Paragraph copyable={{text:jsonText(replay?.contextChunks)}} ellipsis={{rows:4,expandable:true,symbol:'展开上下文'}}>上下文：{jsonText(replay?.contextChunks)}</Typography.Paragraph>
      </Card>
    </Modal>
  </section>;
}
