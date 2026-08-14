import React,{useEffect,useRef,useState} from 'react';
import {Button,Card,Descriptions,Empty,Input,Modal,Popconfirm,Select,Space,Statistic,Table,Tag,Typography,message} from 'antd';
import * as echarts from 'echarts';
import {adminApi,evalApi,kbApi} from '../api/api';
import type {Dataset,EvalCaseResult,EvalComparison,EvalItem,EvalReport,EvalTask,RetrievalReplay} from '../types';

const sample='[\n  {"question":"年假如何申请？","expectedChunkIds":[1],"expectedAnswer":"直属主管","category":"制度问答","sourceTraceId":"","failureStage":""}\n]';
const time=(value:string)=>new Date(value).toLocaleString('zh-CN',{hour12:false});
const brief=(value:unknown)=>Array.isArray(value)?value.join(', '):String(value??'-');
const fixed=(value:unknown)=>Number(value).toFixed(3);
const jsonText=(value?:string)=>value&&value!=='[]'?value:'[]';

export function EvalPage({initialDatasetId}:{initialDatasetId?:number}){
  const [datasets,setDatasets]=useState<Dataset[]>([]);
  const [datasetId,setDatasetId]=useState<number>();
  const [raw,setRaw]=useState(sample);
  const [saved,setSaved]=useState<EvalItem[]>([]);
  const [tasks,setTasks]=useState<EvalTask[]>([]);
  const [report,setReport]=useState<EvalReport>();
  const [comparisons,setComparisons]=useState<EvalComparison[]>([]);
  const [running,setRunning]=useState(false);
  const [saving,setSaving]=useState(false);
  const [replay,setReplay]=useState<RetrievalReplay>();
  const [replayLoading,setReplayLoading]=useState(false);
  const radarRef=useRef<HTMLDivElement>(null);

  useEffect(()=>{kbApi.list().then(setDatasets).catch(()=>undefined)},[]);
  useEffect(()=>{if(initialDatasetId)setDatasetId(initialDatasetId)},[initialDatasetId]);
  useEffect(()=>{if(!datasetId)return;refresh(datasetId).catch(()=>undefined)},[datasetId]);
  useEffect(()=>{
    if(!report||!radarRef.current)return;
    const chart=echarts.init(radarRef.current);
    const values=[report.recallAt5,report.mrr,report.contextPrecision,report.contextRecall,report.answerRelevance,report.faithfulness]
      .map(value=>Math.max(0,Math.min(1,Number(value)||0)));
    chart.setOption({
      radar:{radius:'68%',indicator:[
        {name:'Recall@5',max:1},{name:'MRR',max:1},{name:'Context Precision',max:1},
        {name:'Context Recall',max:1},{name:'Answer Relevance',max:1},{name:'Faithfulness',max:1}
      ],splitArea:{areaStyle:{color:['#fbfaf5','#f1eee5']}},axisName:{color:'#29372a'}},
      series:[{type:'radar',areaStyle:{color:'rgba(232,93,63,.22)'},lineStyle:{color:'#e85d3f',width:2},itemStyle:{color:'#e85d3f'},data:[{value:values,name:'RAG 质量'}]}]
    });
    const resize=()=>chart.resize();
    window.addEventListener('resize',resize);
    return()=>{window.removeEventListener('resize',resize);chart.dispose()};
  },[report]);

  const refresh=async(id:number)=>{
    const [items,history]=await Promise.all([evalApi.list(id),evalApi.tasks(id)]);
    setSaved(items);
    setTasks(history);
    if(items.length>0)setRaw(JSON.stringify(items,null,2));
  };
  const parse=()=>{
    const items=JSON.parse(raw) as EvalItem[];
    if(!Array.isArray(items)||items.length===0)throw new Error('评测集不能为空');
    items.forEach((item,index)=>{
      if(!item.question?.trim())throw new Error(`第 ${index+1} 条缺少 question`);
      if(!Array.isArray(item.expectedChunkIds))throw new Error(`第 ${index+1} 条 expectedChunkIds 必须是数组`);
    });
    return items;
  };
  const run=async()=>{if(!datasetId)return message.warning('请选择知识库');try{setRunning(true);setReport(await evalApi.run(datasetId,parse()));}catch(error){message.error(error instanceof Error?error.message:'评测集格式错误')}finally{setRunning(false)}};
  const compare=async()=>{if(!datasetId)return message.warning('请选择知识库');try{setRunning(true);setComparisons(await evalApi.compare(datasetId,parse(),[1,3,5,8,10]));message.success('离线 TopK 对照已完成');}catch(error){message.error(error instanceof Error?error.message:'离线对照失败')}finally{setRunning(false)}};
  const save=async()=>{if(!datasetId)return message.warning('请选择知识库');try{const items=parse();setSaving(true);for(const item of items){if(item.id)await evalApi.update(item.id,item);else await evalApi.save(datasetId,item)}await refresh(datasetId);message.success(`已保存 ${items.length} 条评测样本`);}catch(error){message.error(error instanceof Error?error.message:'保存失败')}finally{setSaving(false)}};
  const bootstrap=async()=>{if(!datasetId)return message.warning('请选择知识库');setSaving(true);try{const result=await evalApi.bootstrap(datasetId,20);await refresh(datasetId);if(result.items.length>0)setRaw(JSON.stringify(result.items,null,2));message.success(result.created>0?`已从文档生成 ${result.created} 条评测样本`:'没有可生成的新评测样本')}catch(error){message.error(error instanceof Error?error.message:'生成评测集失败')}finally{setSaving(false)}};
  const securityRedTeam=async()=>{if(!datasetId)return message.warning('请选择知识库');setSaving(true);try{const result=await evalApi.securityRedTeam(datasetId);await refresh(datasetId);if(result.items.length>0)setRaw(JSON.stringify(result.items,null,2));message.success(result.created>0?`已生成 ${result.created} 条安全红队样本`:'安全红队样本已存在')}catch(error){message.error(error instanceof Error?error.message:'生成安全红队样本失败')}finally{setSaving(false)}};
  const edit=(item:EvalItem)=>setRaw(JSON.stringify([item],null,2));
  const remove=async(item:EvalItem)=>{if(!datasetId||!item.id)return;try{await evalApi.remove(item.id);await refresh(datasetId);message.success('评测样本已删除')}catch(error){message.error(error instanceof Error?error.message:'删除失败')}};
  const runSaved=async()=>{if(!datasetId)return message.warning('请选择知识库');if(saved.length===0)return message.warning('当前知识库没有已保存评测样本');setRunning(true);try{const result=await evalApi.runSaved(datasetId);setReport(result.report);setTasks(await evalApi.tasks(datasetId));message.success(`评测任务 #${result.taskId} 已完成`);}catch(error){message.error(error instanceof Error?error.message:'运行已保存评测失败')}finally{setRunning(false)}};
  const openTaskReport=async(task:EvalTask)=>{setRunning(true);try{setReport(await evalApi.report(task.id));message.success(`已打开评测任务 #${task.id}`);}catch(error){message.error(error instanceof Error?error.message:'报告读取失败')}finally{setRunning(false)}};
  const openReplay=async(traceId?:string)=>{if(!traceId)return message.warning('该样本没有来源 Trace');setReplayLoading(true);try{const data=await adminApi.replay(traceId);setReplay(data);if(!data.found)message.warning('没有找到该 trace 的检索记录')}catch(error){message.error(error instanceof Error?error.message:'Trace 回放失败')}finally{setReplayLoading(false)}};

  return <section>
    <div className="eyebrow">EVALUATION / REGRESSION</div>
    <h1>检索评测</h1>
    <Card>
      <Space wrap>
        <Select style={{minWidth:220}} placeholder="选择知识库" value={datasetId} onChange={setDatasetId} options={datasets.map(d=>({value:d.id,label:d.name}))}/>
        <Button type="primary" loading={running} onClick={run}>运行当前 JSON</Button>
        <Button loading={running} onClick={compare}>运行离线 TopK 对照</Button>
        <Button loading={saving} onClick={save}>保存/更新评测集</Button>
        <Button loading={saving} onClick={bootstrap}>从文档生成评测集</Button>
        <Button loading={saving} onClick={securityRedTeam}>生成安全红队样本</Button>
        <Button loading={running} onClick={runSaved}>运行已保存评测集</Button>
        <Tag>已保存 {saved.length} 条</Tag>
      </Space>
      <Input.TextArea value={raw} onChange={e=>setRaw(e.target.value)} autoSize={{minRows:8,maxRows:16}} placeholder="[{id?, question, expectedChunkIds, expectedAnswer, shouldRefuse, category, sourceTraceId, failureStage}]"/>
    </Card>
    <Card title="已保存评测样本" className="monitor">
      <Table size="small" rowKey={(item:EvalItem)=>item.id||item.question} dataSource={saved} pagination={{pageSize:6,size:'small'}} columns={[
        {title:'ID',dataIndex:'id',width:80,render:value=>value?`#${value}`:'-'},
        {title:'问题',dataIndex:'question',render:value=><span>{value}</span>},
        {title:'期望 Chunk',dataIndex:'expectedChunkIds',width:150,render:value=><span>{(value||[]).join(', ')}</span>},
        {title:'分类',dataIndex:'category',width:130,render:value=><Tag color={value==='bad-case'?'red':'blue'}>{value||'未分类'}</Tag>},
        {title:'来源 Trace',width:170,render:(_,record)=><Space direction="vertical" size={0}>{record.sourceTraceId?<Space size={4}><Tag>{record.sourceTraceId.slice(0,8)}</Tag><Button size="small" loading={replayLoading} onClick={()=>openReplay(record.sourceTraceId)}>回放</Button></Space>:<span>-</span>}{record.failureStage&&<Tag color={record.failureStage==='ANSWER_USE'?'green':'red'}>{record.failureStage}</Tag>}</Space>},
        {title:'拒答',dataIndex:'shouldRefuse',width:80,render:value=><Tag color={value?'orange':'green'}>{value?'是':'否'}</Tag>},
        {title:'操作',width:150,render:(_,record)=><Space><Button size="small" onClick={()=>edit(record)}>编辑</Button><Popconfirm title="删除该评测样本？" onConfirm={()=>remove(record)}><Button size="small" danger disabled={!record.id}>删除</Button></Popconfirm></Space>}
      ]}/>
    </Card>
    {comparisons.length>0&&<Card title="离线 TopK 检索对照" className="monitor"><Table size="small" rowKey="variant" dataSource={comparisons} pagination={false} columns={[
      {title:'变体',dataIndex:'variant',render:value=><Tag color="blue">{value}</Tag>},
      {title:'样本',dataIndex:'answerable'},
      {title:'Recall@Final',dataIndex:'recallAtFinal',render:fixed},
      {title:'Recall@Fused',dataIndex:'recallAtFused',render:fixed},
      {title:'MRR',dataIndex:'mrr',render:fixed},
      {title:'Context Precision',dataIndex:'contextPrecision',render:fixed},
      {title:'Context Recall',dataIndex:'contextRecall',render:fixed},
      {title:'NDCG',dataIndex:'ndcg',render:fixed},
      {title:'Bad cases',dataIndex:'badCases',render:value=><Tag color={value?.length?'red':'green'}>{value?.length||0}</Tag>}
    ]}/></Card>}
    {report?<>
      <Card title="RAGAS 质量雷达图" className="monitor"><div ref={radarRef} style={{height:360}}/></Card>
      <div className="monitor-cards">
        <Card><Statistic title="样本数" value={report.total}/></Card>
        <Card><Statistic title="Recall@5" value={report.recallAt5} precision={3}/></Card>
        <Card><Statistic title="Recall@20" value={report.recallAt20} precision={3}/></Card>
        <Card><Statistic title="MRR" value={report.mrr} precision={3}/></Card>
        <Card><Statistic title="Context Precision" value={report.contextPrecision} precision={3}/></Card>
        <Card><Statistic title="Context Recall" value={report.contextRecall} precision={3}/></Card>
        <Card><Statistic title="Answer Relevance" value={report.answerRelevance} precision={3}/></Card>
        <Card><Statistic title="NDCG" value={report.ndcg} precision={3}/></Card>
        <Card><Statistic title="拒答率" value={report.refusalRate} precision={3}/></Card>
        <Card><Statistic title="答案准确率" value={report.answerAccuracy} precision={3}/></Card>
        <Card><Statistic title="Faithfulness" value={report.faithfulness} precision={3}/></Card>
      </div>
      <Card title="评测参数快照" className="monitor"><Space wrap>{Object.entries(report.parameters||{}).map(([key,value])=><Tag key={key}>{key}: {brief(value)}</Tag>)}</Space></Card>
      <Card title="逐样本检索明细" className="monitor">
        <Table size="small" rowKey={(item:EvalCaseResult,index)=>`${item.sourceTraceId||item.question}-${index}`} dataSource={report.caseResults||[]} pagination={{pageSize:6,size:'small'}} scroll={{x:1420}} columns={[
          {title:'问题',dataIndex:'question',width:220,ellipsis:true},
          {title:'阶段',dataIndex:'failureStage',width:140,render:value=><Tag color={value==='ANSWER_USE'||value==='EXPECTED_REFUSAL'?'green':'red'}>{value}</Tag>},
          {title:'诊断 / 建议',width:260,render:(_,record)=><Space direction="vertical" size={2}><span>{record.diagnosis||'-'}</span>{(record.actionHints||[]).slice(0,2).map(item=><Typography.Text key={item} type="secondary">· {item}</Typography.Text>)}</Space>},
          {title:'来源 Trace',dataIndex:'sourceTraceId',width:120,render:value=>value?<Button size="small" loading={replayLoading} onClick={()=>openReplay(value)}>{value.slice(0,8)}</Button>:'-'},
          {title:'Final Rank',dataIndex:'rankAtFinal',width:100},
          {title:'Fused Rank',dataIndex:'rankAtFused',width:100},
          {title:'命中',width:120,render:(_,record)=><Space><Tag color={record.hitAt5?'green':'red'}>Top5</Tag><Tag color={record.hitAt20?'green':'red'}>Top20</Tag></Space>},
          {title:'期望 Chunk',dataIndex:'expectedChunkIds',width:160,render:brief},
          {title:'最终上下文',dataIndex:'finalChunkIds',width:160,render:brief},
          {title:'RRF 候选',dataIndex:'fusedChunkIds',width:180,render:brief},
          {title:'向量/BM25',width:220,render:(_,record)=><span>V: {brief(record.vectorChunkIds)} / B: {brief(record.bm25ChunkIds)}</span>},
          {title:'拒答',width:90,render:(_,record)=><Tag color={record.refused?'orange':'green'}>{record.refused?'是':'否'}</Tag>}
        ]}/>
      </Card>
      <Card title="Bad cases" className="monitor">{report.badCases.length===0?<Tag color="green">无 bad case</Tag>:report.badCases.map(item=><p key={item}>{item}</p>)}</Card>
    </>:<Empty description="选择知识库并运行评测"/>}
    <Card title="评测任务历史" className="monitor">
      <Table size="small" rowKey="id" dataSource={tasks} pagination={{pageSize:6,size:'small'}} columns={[
        {title:'任务',dataIndex:'id',width:90,render:value=>`#${value}`},
        {title:'状态',dataIndex:'status',width:100,render:value=><Tag color={value==='DONE'?'green':'gold'}>{value}</Tag>},
        {title:'创建时间',dataIndex:'createdAt',width:180,render:value=>time(value)},
        {title:'报告',dataIndex:'report',render:value=><span>{String(value).slice(0,180)}</span>},
        {title:'操作',width:120,render:(_,record)=><Button size="small" loading={running} onClick={()=>openTaskReport(record)}>查看报告</Button>}
      ]}/>
    </Card>
    <Modal open={!!replay} title={`检索回放 ${replay?.traceId||''}`} width={920} footer={<Button onClick={()=>setReplay(undefined)}>关闭</Button>} onCancel={()=>setReplay(undefined)}>
      <Descriptions size="small" bordered column={2}>
        <Descriptions.Item label="知识库">{replay?.datasetId}</Descriptions.Item>
        <Descriptions.Item label="耗时">{replay?.latencyMs||0} ms</Descriptions.Item>
        <Descriptions.Item label="置信度">{replay?.confidence}</Descriptions.Item>
        <Descriptions.Item label="结果">{replay?.refused?<Tag color="red">拒答</Tag>:<Tag color="green">已回答</Tag>}</Descriptions.Item>
        <Descriptions.Item label="阶段计数" span={2}>{Object.entries(replay?.stageCounts||{}).map(([key,value])=><Tag key={key}>{key}:{value}</Tag>)}</Descriptions.Item>
        <Descriptions.Item label="问题阶段" span={2}><Tag color={replay?.failureStage==='ANSWER_USE'?'green':'red'}>{replay?.failureStage||'UNKNOWN'}</Tag></Descriptions.Item>
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
      <Card size="small" title="最终证据摘要" className="monitor">
        {(replay?.evidencePreview||[]).length===0?<Typography.Text type="secondary">无最终上下文证据</Typography.Text>:(replay?.evidencePreview||[]).map(item=><Card key={`${item.chunkId}-${item.rank}`} size="small" style={{marginBottom:8}}><Space wrap><Tag>#{item.chunkId}</Tag><Tag>rank {item.rank}</Tag><Tag>{item.channel||'context'}</Tag><Tag>{Number(item.score||0).toFixed(3)}</Tag></Space><Typography.Paragraph style={{marginBottom:0,whiteSpace:'pre-wrap'}}>{item.excerpt}</Typography.Paragraph></Card>)}
      </Card>
      <Card size="small" title="向量 Top-N / BM25 Top-N / RRF / Rerank / MMR / Small-to-Big / 最终上下文" className="monitor">
        <Typography.Paragraph copyable={{text:jsonText(replay?.vectorResults)}} ellipsis={{rows:2,expandable:true,symbol:'展开向量召回'}}>向量：{jsonText(replay?.vectorResults)}</Typography.Paragraph>
        <Typography.Paragraph copyable={{text:jsonText(replay?.bm25Results)}} ellipsis={{rows:2,expandable:true,symbol:'展开 BM25'}}>BM25：{jsonText(replay?.bm25Results)}</Typography.Paragraph>
        <Typography.Paragraph copyable={{text:jsonText(replay?.fusedResults)}} ellipsis={{rows:2,expandable:true,symbol:'展开 RRF'}}>RRF：{jsonText(replay?.fusedResults)}</Typography.Paragraph>
        <Typography.Paragraph copyable={{text:jsonText(replay?.rerankResults)}} ellipsis={{rows:2,expandable:true,symbol:'展开 Rerank'}}>Rerank：{jsonText(replay?.rerankResults)}</Typography.Paragraph>
        <Typography.Paragraph copyable={{text:jsonText(replay?.mmrResults)}} ellipsis={{rows:2,expandable:true,symbol:'展开 MMR'}}>MMR：{jsonText(replay?.mmrResults)}</Typography.Paragraph>
        <Typography.Paragraph copyable={{text:jsonText(replay?.smallToBigContext)}} ellipsis={{rows:3,expandable:true,symbol:'展开 Small-to-Big'}}>Small-to-Big：{jsonText(replay?.smallToBigContext)}</Typography.Paragraph>
        <Typography.Paragraph copyable={{text:jsonText(replay?.contextChunks)}} ellipsis={{rows:4,expandable:true,symbol:'展开上下文'}}>上下文：{jsonText(replay?.contextChunks)}</Typography.Paragraph>
      </Card>
    </Modal>
  </section>;
}
