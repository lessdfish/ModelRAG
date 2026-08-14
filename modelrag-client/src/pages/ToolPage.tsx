import React,{useEffect,useState} from 'react';
import {Alert,Button,Card,Form,Input,InputNumber,Modal,Popconfirm,Select,Space,Switch,Table,Tag,Typography,message} from 'antd';
import {ApiOutlined,PlusOutlined,ReloadOutlined} from '@ant-design/icons';
import {intentApi,kbApi,toolApi} from '../api/api';
import type {Dataset,IntentNode,ToolDefinition} from '../types';

type ToolForm=ToolDefinition;
type IntentForm=IntentNode;

export function ToolPage({admin}:{admin:boolean}){
  const [items,setItems]=useState<ToolDefinition[]>([]);
  const [datasets,setDatasets]=useState<Dataset[]>([]);
  const [datasetId,setDatasetId]=useState<number>();
  const [intents,setIntents]=useState<IntentNode[]>([]);
  const [loading,setLoading]=useState(false);
  const [open,setOpen]=useState(false);
  const [intentOpen,setIntentOpen]=useState(false);
  const [editingIntent,setEditingIntent]=useState<IntentNode>();
  const [saving,setSaving]=useState(false);
  const [form]=Form.useForm<ToolForm>();
  const [intentForm]=Form.useForm<IntentForm>();
  const type=Form.useWatch('type',form)||'HTTP';
  const targetType=Form.useWatch('targetType',intentForm)||'TOOL';
  const load=async()=>{setLoading(true);try{const [tools,kbs]=await Promise.all([admin?toolApi.all():toolApi.list(),kbApi.list()]);setItems(tools);setDatasets(kbs);const selected=datasetId||kbs[0]?.id;setDatasetId(selected);if(admin&&selected)setIntents(await intentApi.list(selected));else setIntents([]);}catch(error){message.error(error instanceof Error?error.message:'工具列表加载失败')}finally{setLoading(false)}};
  useEffect(()=>{load()},[]);
  useEffect(()=>{if(admin&&datasetId)intentApi.list(datasetId).then(setIntents).catch(()=>setIntents([]));else setIntents([])},[admin,datasetId]);
  const create=()=>{form.setFieldsValue({type:'HTTP',riskLevel:'LOW',enabled:true,allowedRoles:[],allowedDatasetIds:[],jsonSchema:'{"type":"object","required":["query"],"properties":{"query":{"type":"string"}}}'});setOpen(true)};
  const createIntent=()=>{if(!datasetId)return message.warning('请先选择知识库');setEditingIntent(undefined);intentForm.setFieldsValue({datasetId,nodeType:'TOPIC',targetType:'TOOL',priority:10,enabled:true});setIntentOpen(true)};
  const editIntent=(item:IntentNode)=>{setEditingIntent(item);intentForm.setFieldsValue(item);setIntentOpen(true)};
  const submit=async(values:ToolForm)=>{
    setSaving(true);
    try{
      await toolApi.register({...values,enabled:values.enabled!==false,authHeaderValue:values.authHeaderValue?.trim()||undefined,jsonSchema:values.jsonSchema?.trim()||undefined,allowedRoles:values.allowedRoles||[],allowedDatasetIds:values.allowedDatasetIds||[]});
      setOpen(false);form.resetFields();await load();message.success('工具已注册，可在意图树中绑定 TOOL 节点使用');
    }catch(error){message.error(error instanceof Error?error.message:'工具注册失败')}
    finally{setSaving(false)}
  };
  const submitIntent=async(values:IntentForm)=>{
    if(!datasetId)return;
    setSaving(true);
    try{
      const payload={...values,datasetId,targetId:values.targetType==='TOOL'?values.targetId:undefined,enabled:values.enabled!==false};
      if(editingIntent?.id)await intentApi.update(datasetId,editingIntent.id,payload);else await intentApi.create(datasetId,payload);
      setIntentOpen(false);setEditingIntent(undefined);intentForm.resetFields();setIntents(await intentApi.list(datasetId));message.success(editingIntent?'意图已更新':'意图已绑定，Agent 会优先按该意图路由');
    }catch(error){message.error(error instanceof Error?error.message:'意图绑定失败')}
    finally{setSaving(false)}
  };
  const toggle=async(item:ToolDefinition,value:boolean)=>{await toolApi.setEnabled(item.name,value,item);await load();message.success(value?'工具已启用':'工具已停用')};
  const remove=async(item:ToolDefinition)=>{await toolApi.remove(item.name);await load();message.success('工具已删除')};
  const removeIntent=async(item:IntentNode)=>{if(!datasetId||!item.id)return;await intentApi.remove(datasetId,item.id);setIntents(await intentApi.list(datasetId));message.success('意图已删除')};
  return <section className="tools-page">
    <div className="eyebrow">TOOLS / RPC GOVERNANCE</div>
    <h1>{admin?'工具注册与治理':'可用工具'}</h1>
    <Alert showIcon type="info" message={admin?'低风险 HTTP 工具可由 Agent 自动调用；高风险工具会进入审批流。鉴权 Header 值只在创建/更新时提交，列表中不会明文展示。':'这里仅展示你当前账号可用的工具。低风险工具可由 Agent 自动调用，高风险工具会进入审批流。'}/>
    <Card className="monitor" title="工具目录" extra={<Space><Button icon={<ReloadOutlined/>} loading={loading} onClick={load}>刷新</Button>{admin&&<Button type="primary" icon={<PlusOutlined/>} onClick={create}>注册工具</Button>}</Space>}>
      <Table size="small" rowKey="name" loading={loading} dataSource={items} pagination={{pageSize:8,size:'small'}} columns={[
        {title:'工具',dataIndex:'name',render:(value,record)=><Space direction="vertical" size={0}><Typography.Text strong>{value}</Typography.Text><Typography.Text type="secondary">{record.description||'无说明'}</Typography.Text></Space>},
        {title:'类型',dataIndex:'type',width:105,render:value=><Tag icon={<ApiOutlined/>} color={value==='HTTP'?'blue':'default'}>{value}</Tag>},
        {title:'风险',dataIndex:'riskLevel',width:90,render:value=><Tag color={value==='HIGH'?'red':'green'}>{value}</Tag>},
        {title:'Endpoint',dataIndex:'endpoint',ellipsis:true,render:value=>value||'-'},
        {title:'Schema',dataIndex:'jsonSchema',width:95,render:value=><Tag color={value&&value!=='{}'?'purple':'default'}>{value&&value!=='{}'?'已配置':'默认'}</Tag>},
        {title:'鉴权',width:140,render:(_,record)=><Tag color={record.authHeaderName?'gold':'default'}>{record.authHeaderName?`${record.authHeaderName}: ${record.authHeaderValue||'******'}`:'无'}</Tag>},
        {title:'调用权限',width:180,render:(_,record)=><Space direction="vertical" size={0}><Tag color={record.allowedRoles?.length?'volcano':'default'}>{record.allowedRoles?.length?`角色 ${record.allowedRoles.join('/')}`:'不限角色'}</Tag><Tag color={record.allowedDatasetIds?.length?'geekblue':'default'}>{record.allowedDatasetIds?.length?`知识库 ${record.allowedDatasetIds.join(',')}`:'不限知识库'}</Tag></Space>},
        {title:'启用',dataIndex:'enabled',width:90,render:(value,record)=>admin?<Switch checked={value} onChange={checked=>toggle(record,checked)}/>:<Tag color={value?'green':'default'}>{value?'可用':'停用'}</Tag>},
        ...(admin?[{title:'操作',width:90,render:(_:unknown,record:ToolDefinition)=><Popconfirm title="删除该工具？已绑定的意图将无法调用。" onConfirm={()=>remove(record)}><Button size="small" danger disabled={['knowledge_lookup','destructive_operation'].includes(record.name)}>删除</Button></Popconfirm>}]:[])
      ]}/>
    </Card>
    {admin&&<Card className="monitor" title="知识库意图绑定" extra={<Space><Select style={{minWidth:220}} placeholder="选择知识库" value={datasetId} onChange={setDatasetId} options={datasets.map(item=>({value:item.id,label:item.name}))}/><Button type="primary" icon={<PlusOutlined/>} onClick={createIntent}>绑定意图</Button></Space>}>
      <Table size="small" rowKey={(item:IntentNode)=>item.id||`${item.name}-${item.targetType}-${item.targetId}`} loading={loading} dataSource={intents} pagination={{pageSize:6,size:'small'}} columns={[
        {title:'意图词',dataIndex:'name',render:(value,record)=><Space direction="vertical" size={0}><Typography.Text strong>{value}</Typography.Text><Typography.Text type="secondary">{record.description||'问题中包含该词时命中'}</Typography.Text></Space>},
        {title:'目标',dataIndex:'targetType',width:110,render:value=><Tag color={value==='TOOL'?'orange':value==='RAG'?'green':'default'}>{value}</Tag>},
        {title:'绑定对象',dataIndex:'targetId',render:value=>value||'-'},
        {title:'优先级',dataIndex:'priority',width:90},
        {title:'状态',dataIndex:'enabled',width:90,render:value=><Tag color={value?'green':'default'}>{value?'启用':'停用'}</Tag>},
        {title:'操作',width:140,render:(_,record)=><Space><Button size="small" onClick={()=>editIntent(record)}>编辑</Button><Popconfirm title="删除该意图绑定？" onConfirm={()=>removeIntent(record)}><Button size="small" danger disabled={!record.id}>删除</Button></Popconfirm></Space>}
      ]}/>
      <Typography.Paragraph type="secondary">例：知识库“订单制度库”绑定意图词“订单”，目标选择某个 HTTP 工具。用户问“查订单 1001”时，自动选中该知识库并进入 Agent 调用工具。</Typography.Paragraph>
    </Card>}
    <Modal open={open} title="注册 HTTP/RPC 工具" okText="保存" confirmLoading={saving} onOk={()=>form.submit()} onCancel={()=>setOpen(false)} destroyOnClose>
      <Form form={form} layout="vertical" onFinish={submit} preserve={false}>
        <Form.Item name="name" label="工具名称" rules={[{required:true,message:'请输入工具名称'}]}><Input placeholder="external_order_lookup"/></Form.Item>
        <Form.Item name="description" label="说明"><Input.TextArea placeholder="用于查询外部订单、工单或审批系统"/></Form.Item>
        <Space align="start" wrap>
          <Form.Item name="type" label="类型" rules={[{required:true}]}><Select style={{width:140}} options={[{value:'HTTP',label:'HTTP'},{value:'INTERNAL',label:'INTERNAL'}]}/></Form.Item>
          <Form.Item name="riskLevel" label="风险级别" rules={[{required:true}]}><Select style={{width:140}} options={[{value:'LOW',label:'LOW 自动执行'},{value:'HIGH',label:'HIGH 需审批'}]}/></Form.Item>
          <Form.Item name="enabled" label="启用" valuePropName="checked"><Switch/></Form.Item>
        </Space>
        {type==='HTTP'&&<Form.Item name="endpoint" label="HTTP Endpoint" rules={[{required:true,message:'HTTP 工具必须填写 endpoint'}]}><Input placeholder="http://host.docker.internal:18081/tool"/></Form.Item>}
        {type==='HTTP'&&<Space align="start" wrap><Form.Item name="authHeaderName" label="鉴权 Header 名"><Input placeholder="Authorization 或 X-Tool-Token"/></Form.Item><Form.Item name="authHeaderValue" label="鉴权 Header 值"><Input.Password placeholder="Bearer ..."/></Form.Item></Space>}
        <Form.Item name="allowedRoles" label="允许调用角色"><Select mode="tags" placeholder="留空表示不限角色" options={['ADMIN','APPROVER','USER'].map(value=>({value,label:value}))}/></Form.Item>
        <Form.Item name="allowedDatasetIds" label="允许调用知识库"><Select mode="multiple" placeholder="留空表示不限知识库" options={datasets.map(item=>({value:item.id,label:item.name}))}/></Form.Item>
        <Form.Item name="jsonSchema" label="参数 JSON Schema" rules={[{validator:(_,value)=>{if(!value||!value.trim())return Promise.resolve();try{JSON.parse(value);return Promise.resolve()}catch{return Promise.reject(new Error('JSON Schema 必须是合法 JSON'))}}}]}><Input.TextArea autoSize={{minRows:4,maxRows:8}} placeholder='{"type":"object","required":["query"],"properties":{"query":{"type":"string"}}}'/></Form.Item>
        <Typography.Text type="secondary">Agent 调用前会先校验角色与知识库权限，再按 Schema 校验参数。普通文本子任务会兼容成 query/input 字段；外部服务仍收到 {'{'}"tool":"工具名","input":"用户子任务"{'}'}。</Typography.Text>
      </Form>
    </Modal>
    <Modal open={intentOpen} title={editingIntent?'编辑知识库意图':'绑定知识库意图'} okText="保存" confirmLoading={saving} onOk={()=>intentForm.submit()} onCancel={()=>{setIntentOpen(false);setEditingIntent(undefined)}} destroyOnClose>
      <Form form={intentForm} layout="vertical" onFinish={submitIntent} preserve={false}>
        <Form.Item name="name" label="意图词" rules={[{required:true,message:'请输入意图词'}]}><Input placeholder="订单 / 工单 / 审批 / 财务报销"/></Form.Item>
        <Form.Item name="description" label="说明或同义词"><Input.TextArea placeholder="用户问题包含这里的说明文字时也可命中"/></Form.Item>
        <Space align="start" wrap>
          <Form.Item name="targetType" label="目标类型" rules={[{required:true}]}><Select style={{width:150}} options={[{value:'TOOL',label:'TOOL 工具'},{value:'RAG',label:'RAG 知识库'},{value:'DIRECT',label:'DIRECT 直接答复'}]}/></Form.Item>
          <Form.Item name="nodeType" label="节点类型" rules={[{required:true}]}><Select style={{width:140}} options={[{value:'TOPIC',label:'TOPIC'},{value:'TASK',label:'TASK'}]}/></Form.Item>
          <Form.Item name="priority" label="优先级" rules={[{required:true}]}><InputNumber min={0} max={1000}/></Form.Item>
          <Form.Item name="enabled" label="启用" valuePropName="checked"><Switch/></Form.Item>
        </Space>
        {targetType==='TOOL'&&<Form.Item name="targetId" label="选择工具" rules={[{required:true,message:'TOOL 意图必须选择工具'}]}><Select options={items.filter(item=>item.enabled).map(item=>({value:item.name,label:`${item.name} (${item.riskLevel})`}))}/></Form.Item>}
        <Typography.Text type="secondary">匹配规则保持简单可解释：问题文本包含“意图词”或“说明”时命中；同一知识库内按优先级排序。</Typography.Text>
      </Form>
    </Modal>
  </section>;
}
