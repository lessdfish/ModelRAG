import {useEffect,useState} from 'react';
import {Button,Card,Col,Empty,Form,Input,InputNumber,Progress,Row,Select,Space,Switch,Table,Tag,message} from 'antd';
import {ReloadOutlined} from '@ant-design/icons';
import {kbApi,monitorApi} from '../api/api';
import type {Dataset,ModelHealth,TokenUsage} from '../types';

const BUDGET=100000;
type ModelRow=ModelHealth;

export function MonitorPage(){
  const [datasets,setDatasets]=useState<Dataset[]>([]);
  const [datasetId,setDatasetId]=useState<number>();
  const [usage,setUsage]=useState<TokenUsage>();
  const [models,setModels]=useState<ModelRow[]>([]);
  const [modelForm]=Form.useForm<Partial<ModelHealth>>();

  const reloadModels=async()=>setModels((await monitorApi.modelHealth()) as ModelRow[]);
  const loadDatasets=async()=>{
    const items=await kbApi.list();
    setDatasets(items);
    if(!datasetId&&items[0])setDatasetId(items[0].id);
  };
  const loadCurrent=async(id:number)=>setUsage(await monitorApi.overview(id));

  useEffect(()=>{loadDatasets().catch(()=>undefined);reloadModels().catch(()=>setModels([]))},[]);
  useEffect(()=>{if(datasetId)loadCurrent(datasetId).catch(()=>setUsage(undefined))},[datasetId]);

  const percent=Math.min(100,Math.round((usage?.totalTokens||0)*100/BUDGET));
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
      message.success('模型策略已保存');
    }catch(error){message.error(error instanceof Error?error.message:'模型策略保存失败')}
  };

  return <section>
    <div className="eyebrow">OBSERVABILITY / MODEL POLICY</div>
    <h1>知识库用量与模型策略</h1>
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
        <Col span={8}><Card><b>{usage?.summaryTokens||0}</b><p>会话摘要 Token</p></Card></Col>
        <Col span={8}><Card><b>{usage?.estimatedCost||0}</b><p>预估成本（本地模型）</p></Card></Col>
      </Row>
      <Card title="预算状态" className="monitor"><Progress percent={percent} status={usage?.overBudget?'exception':'active'} strokeColor="#e85d3f"/><p>预算阈值：{BUDGET.toLocaleString()} Token</p><Tag color={usage?.overBudget?'red':'green'}>{usage?.overBudget?'已超预算':'预算正常'}</Tag></Card>
      <Card title="模型候选、路由与熔断" className="monitor">
        <Form form={modelForm} layout="inline" onFinish={createModel} initialValues={{modelType:'CHAT',provider:'local',priority:0,enabled:true,canaryPercent:0}}>
          <Form.Item name="modelType"><Select style={{width:120}} options={['CHAT','EMBEDDING','RERANK','ROUTER'].map(value=>({value,label:value}))}/></Form.Item>
          <Form.Item name="modelName" rules={[{required:true,message:'模型名不能为空'}]}><Input placeholder="模型名，例如 qwen3-embedding:0.6b"/></Form.Item>
          <Form.Item name="provider"><Input placeholder="provider" style={{width:110}}/></Form.Item>
          <Form.Item name="priority"><InputNumber min={-1000} max={1000} addonBefore="优先级"/></Form.Item>
          <Form.Item name="canaryPercent"><InputNumber min={0} max={100} addonAfter="%"/></Form.Item>
          <Form.Item name="enabled" valuePropName="checked"><Switch checkedChildren="启用" unCheckedChildren="停用"/></Form.Item>
          <Button htmlType="submit" type="primary">保存模型策略</Button>
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
        ]}/>
        <p className="context-policy">路由只使用已启用且有明确运行时客户端的候选；OPEN 状态跳过，探测窗口后进入 HALF_OPEN。未接入运行时的模型不会静默降级为默认模型。</p>
      </Card>
    </>}
  </section>;
}

function time(value?:string){return value?new Date(value).toLocaleString('zh-CN',{hour12:false}):'-'}
