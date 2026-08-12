import {useEffect,useState} from 'react';
import {Button,Card,Col,Empty,Form,Input,InputNumber,Popconfirm,Progress,Row,Select,Space,Switch,Table,Tag,message} from 'antd';
import {ExperimentOutlined,ReloadOutlined} from '@ant-design/icons';
import {experimentApi,kbApi,monitorApi} from '../api/api';
import type {AbExperiment,AbReport,Dataset,ModelHealth,TokenUsage} from '../types';

const BUDGET=100000;
type ModelRow=ModelHealth;

export function MonitorPage(){
  const [datasets,setDatasets]=useState<Dataset[]>([]);
  const [datasetId,setDatasetId]=useState<number>();
  const [usage,setUsage]=useState<TokenUsage>();
  const [models,setModels]=useState<ModelRow[]>([]);
  const [experiments,setExperiments]=useState<AbExperiment[]>([]);
  const [reports,setReports]=useState<AbReport[]>([]);
  const [saving,setSaving]=useState(false);
  const [form]=Form.useForm<Partial<AbExperiment>>();
  const [modelForm]=Form.useForm<Partial<ModelHealth>>();

  const loadDatasets=async()=>{
    const items=await kbApi.list();
    setDatasets(items);
    if(!datasetId&&items[0])setDatasetId(items[0].id);
  };

  const loadCurrent=async(id:number)=>{
    const [u,e,r]=await Promise.all([monitorApi.overview(id),experimentApi.list(id),experimentApi.report(id)]);
    setUsage(u);setExperiments(e);setReports(r);
  };

  useEffect(()=>{loadDatasets().catch(()=>undefined);reloadModels().catch(()=>setModels([]))},[]);
  useEffect(()=>{if(datasetId)loadCurrent(datasetId).catch(()=>{setUsage(undefined);setExperiments([]);setReports([])})},[datasetId]);

  const percent=Math.min(100,Math.round((usage?.totalTokens||0)*100/BUDGET));
  const reloadModels=async()=>setModels((await monitorApi.modelHealth()) as ModelRow[]);

  const saveExperiment=async(values:Partial<AbExperiment>)=>{
    if(!datasetId)return message.warning('请先选择知识库');
    setSaving(true);
    try{
      await experimentApi.save({...values,datasetId,id:values.id||`topk-${datasetId}-${values.variantTopK||8}`,enabled:values.enabled===true});
      form.resetFields();
      await loadCurrent(datasetId);
      message.success('在线实验已保存');
    }catch(error){message.error(error instanceof Error?error.message:'实验保存失败')}
    finally{setSaving(false)}
  };

  const toggle=async(item:AbExperiment,value:boolean)=>{
    if(!datasetId)return;
    await experimentApi.setEnabled(item.id,value);
    await loadCurrent(datasetId);
  };

  const remove=async(item:AbExperiment)=>{
    if(!datasetId)return;
    await experimentApi.remove(item.id);
    await loadCurrent(datasetId);
    message.success('实验已删除');
  };

  const saveModel=async(row:ModelRow,patch:Partial<ModelRow>)=>{
    try{
      const body={...row,provider:row.provider||'local',priority:row.priority||0,enabled:row.enabled!==false,canaryPercent:row.canaryPercent||0,...patch};
      await monitorApi.saveModelCandidate(body);
      await reloadModels();
      message.success('模型策略已保存');
    }catch(error){message.error(error instanceof Error?error.message:'模型策略保存失败')}
  };

  const createModel=async(values:Partial<ModelHealth>)=>{
    if(!values.modelName?.trim())return message.warning('请输入模型名');
    try{
      await monitorApi.saveModelCandidate({modelType:values.modelType||'CHAT',modelName:values.modelName.trim(),provider:values.provider||'local',priority:values.priority||0,enabled:values.enabled!==false,canaryPercent:values.canaryPercent||0});
      modelForm.resetFields();
      await reloadModels();
      message.success('模型候选已保存，可在列表中调整优先级、灰度和启用状态');
    }catch(error){message.error(error instanceof Error?error.message:'模型候选保存失败')}
  };

  return <section>
    <div className="eyebrow">OBSERVABILITY / EVALUATION</div>
    <h1>知识库用量与在线实验</h1>
    <Space className="monitor-toolbar" wrap>
      <Select placeholder="选择知识库" value={datasetId} onChange={setDatasetId} options={datasets.map(d=>({value:d.id,label:d.name}))} style={{minWidth:240}}/>
      <Button icon={<ReloadOutlined/>} onClick={()=>datasetId&&loadCurrent(datasetId)}>刷新</Button>
    </Space>
    {!datasetId?<Empty description="请先创建知识库"/>:<>
      <Row gutter={16} className="monitor-cards">
        <Col span={8}><Card><b>{usage?.promptTokens||0}</b><p>输入 Token</p></Card></Col>
        <Col span={8}><Card><b>{usage?.completionTokens||0}</b><p>输出 Token</p></Card></Col>
        <Col span={8}><Card><b>{usage?.embeddingTokens||0}</b><p>Embedding Token</p></Card></Col>
        <Col span={8}><Card><b>{usage?.rerankCalls||0}</b><p>Rerank 调用</p></Card></Col>
        <Col span={8}><Card><b>{usage?.estimatedCost||0}</b><p>预估成本（本地模型）</p></Card></Col>
      </Row>
      <Card title="预算状态" className="monitor"><Progress percent={percent} status={usage?.overBudget?'exception':'active'} strokeColor="#e85d3f"/><p>本地预算阈值：{BUDGET.toLocaleString()} Token</p><Tag color={usage?.overBudget?'red':'green'}>{usage?.overBudget?'已超预算':'预算正常'}</Tag></Card>
      <Card title="在线 A/B 实验" className="monitor" extra={<Tag color="blue">按 query 稳定分桶</Tag>}>
        <Form form={form} layout="inline" onFinish={saveExperiment} initialValues={{name:'TopK 影子实验',trafficPercent:10,variantTopK:8,enabled:false}}>
          <Form.Item name="name" rules={[{required:true,message:'实验名不能为空'}]}><Input placeholder="实验名"/></Form.Item>
          <Form.Item name="trafficPercent" rules={[{required:true,message:'流量必填'}]}><InputNumber min={0} max={100} addonAfter="%"/></Form.Item>
          <Form.Item name="variantTopK" rules={[{required:true,message:'TopK 必填'}]}><InputNumber min={1} max={20} addonBefore="TopK"/></Form.Item>
          <Form.Item name="enabled" valuePropName="checked"><Switch checkedChildren="启用" unCheckedChildren="停用"/></Form.Item>
          <Button type="primary" htmlType="submit" loading={saving} icon={<ExperimentOutlined/>}>保存实验</Button>
        </Form>
        <Table size="small" rowKey="id" dataSource={experiments} pagination={false} className="ab-table" columns={[
          {title:'实验',render:(_,row)=><Space direction="vertical" size={0}><b>{row.name}</b><span>{row.id}</span></Space>},
          {title:'流量',dataIndex:'trafficPercent',width:90,render:value=>`${value}%`},
          {title:'变体 TopK',dataIndex:'variantTopK',width:100},
          {title:'状态',width:120,render:(_,row)=><Switch checked={row.enabled} checkedChildren="启用" unCheckedChildren="停用" onChange={value=>toggle(row,value)}/>},
          {title:'操作',width:90,render:(_,row)=><Popconfirm title="删除该实验？" onConfirm={()=>remove(row)}><Button danger size="small">删除</Button></Popconfirm>}
        ]}/>
      </Card>
      <Card title="A/B 实验报告" className="monitor">
        <Table size="small" rowKey={(item:AbReport)=>`${item.experimentId}-${item.variant}`} dataSource={reports} pagination={{pageSize:6,size:'small'}} columns={[
          {title:'实验',dataIndex:'experimentId',ellipsis:true},
          {title:'变体',dataIndex:'variant',width:130},
          {title:'样本',dataIndex:'samples',width:80},
          {title:'平均置信度',dataIndex:'avgConfidence',width:120,render:fixed},
          {title:'拒答率',dataIndex:'refusalRate',width:100,render:rate},
          {title:'平均差异片段',dataIndex:'avgDeltaCount',width:130,render:fixed},
          {title:'平均延迟',dataIndex:'avgLatencyMs',width:110,render:(value:number)=>`${Math.round(value||0)}ms`},
          {title:'最近命中',dataIndex:'lastSeen',width:170,render:time}
        ]}/>
      </Card>
      <Card title="模型候选、灰度与熔断" className="monitor">
        <Form form={modelForm} layout="inline" onFinish={createModel} initialValues={{modelType:'CHAT',provider:'local',priority:0,enabled:true,canaryPercent:0}}>
          <Form.Item name="modelType"><Select style={{width:120}} options={['CHAT','EMBEDDING','RERANK','ROUTER'].map(value=>({value,label:value}))}/></Form.Item>
          <Form.Item name="modelName" rules={[{required:true,message:'模型名不能为空'}]}><Input placeholder="模型名，例如 deepseek-r1:1.5b"/></Form.Item>
          <Form.Item name="provider"><Input placeholder="provider" style={{width:110}}/></Form.Item>
          <Form.Item name="priority"><InputNumber min={-1000} max={1000} addonBefore="优先级"/></Form.Item>
          <Form.Item name="canaryPercent"><InputNumber min={0} max={100} addonAfter="%"/></Form.Item>
          <Form.Item name="enabled" valuePropName="checked"><Switch checkedChildren="启用" unCheckedChildren="停用"/></Form.Item>
          <Button htmlType="submit" type="primary">保存模型候选</Button>
        </Form>
        <Table size="small" rowKey={(item:ModelRow)=>`${item.modelType}-${item.modelName}`} dataSource={models} pagination={{pageSize:6,size:'small'}} columns={[
        {title:'类型',dataIndex:'modelType',width:120,render:value=><Tag>{value}</Tag>},
        {title:'模型',dataIndex:'modelName',ellipsis:true,render:(value,record:ModelRow)=><Space direction="vertical" size={0}><span>{value}</span><small>{record.provider||'local'} · {record.availableRuntime?'运行时可用':'仅有策略记录'}</small></Space>},
        {title:'启用',width:90,render:(_,row:ModelRow)=><Switch checked={row.enabled!==false} onChange={value=>saveModel(row,{enabled:value})}/>},
        {title:'优先级',width:110,render:(_,row:ModelRow)=><InputNumber size="small" min={-1000} max={1000} defaultValue={row.priority||0} onPressEnter={event=>saveModel(row,{priority:Number((event.target as HTMLInputElement).value||0)})} onBlur={event=>saveModel(row,{priority:Number(event.target.value||0)})}/>},
        {title:'灰度',width:120,render:(_,row:ModelRow)=><InputNumber size="small" min={0} max={100} addonAfter="%" defaultValue={row.canaryPercent||0} onPressEnter={event=>saveModel(row,{canaryPercent:Number((event.target as HTMLInputElement).value||0)})} onBlur={event=>saveModel(row,{canaryPercent:Number(event.target.value||0)})}/>},
        {title:'状态',dataIndex:'state',width:100,render:value=><Tag color={value==='OPEN'?'red':value==='HALF_OPEN'?'gold':'green'}>{value}</Tag>},
        {title:'失败次数',dataIndex:'failures',width:100},
        {title:'下次探测',dataIndex:'nextProbeAt',width:180,render:time}
      ]}/><p className="context-policy">路由顺序：先过滤未启用候选，再按优先级排序；命中灰度桶时 canary 候选提前；OPEN 状态会跳过，过探测窗口后进入 HALF_OPEN。新增但没有运行时客户端的候选会标记为“仅有策略记录”，用于提前配置灰度策略。</p></Card>
    </>}
  </section>;
}

function fixed(value:number){return Number(value||0).toFixed(3)}
function rate(value:number){return `${Math.round(Number(value||0)*100)}%`}
function time(value?:string){return value?new Date(value).toLocaleString('zh-CN',{hour12:false}):'-'}
