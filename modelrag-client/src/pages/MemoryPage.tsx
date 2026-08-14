import {useEffect,useMemo,useState} from 'react';
import {Alert,Button,Card,Form,Input,InputNumber,Modal,Select,Space,Switch,Table,Tag,Typography,message} from 'antd';
import {kbApi,memoryApi} from '../api/api';
import type {Dataset,MemoryRecord,MemorySettings} from '../types';

type MemoryForm={datasetId?:number;type:MemoryRecord['type'];memoryKey?:string;content:string};

export function MemoryPage(){
  const [items,setItems]=useState<MemoryRecord[]>([]);
  const [datasets,setDatasets]=useState<Dataset[]>([]);
  const [settings,setSettings]=useState<MemorySettings>({enabled:false,retentionDays:180});
  const [loading,setLoading]=useState(true);
  const [editing,setEditing]=useState<MemoryRecord>();
  const [open,setOpen]=useState(false);
  const [form]=Form.useForm<MemoryForm>();
  const typeOptions=useMemo(()=>[
    {value:'PREFERENCE',label:'回答偏好'},{value:'PROFILE',label:'用户资料'},{value:'BUSINESS_FACT',label:'知识库业务事实'}
  ],[]);

  const load=async()=>{
    setLoading(true);
    try{
      const [memoryRows,datasetRows,currentSettings]=await Promise.all([memoryApi.list(),kbApi.list(),memoryApi.settings()]);
      setItems(memoryRows);setDatasets(datasetRows);setSettings(currentSettings);
    }catch(error){message.error(error instanceof Error?error.message:'长期记忆加载失败')}finally{setLoading(false)}
  };
  useEffect(()=>{void load()},[]);
  const edit=(item?:MemoryRecord)=>{
    setEditing(item);setOpen(true);
    form.setFieldsValue(item?{datasetId:item.datasetId,type:item.type,memoryKey:item.memoryKey,content:item.content}:{type:'PREFERENCE'});
  };
  const save=async(values:MemoryForm)=>{
    if(values.type==='BUSINESS_FACT'&&!values.datasetId){message.error('业务事实必须绑定知识库');return}
    try{
      if(editing)await memoryApi.update(editing,values);else await memoryApi.remember(values);
      setOpen(false);setEditing(undefined);form.resetFields();await load();message.success(editing?'记忆已更新':'已明确记住');
    }catch(error){message.error(error instanceof Error?error.message:'记忆保存失败')}
  };
  const action=async(run:()=>Promise<unknown>,success:string)=>{try{await run();await load();message.success(success)}catch(error){message.error(error instanceof Error?error.message:'操作失败')}};
  const saveSettings=async(next:MemorySettings)=>{
    if(next.enabled&&!settings.enabled){
      Modal.confirm({title:'启用长期记忆？',content:'系统会保存你明确要求记住的偏好，以及模型提出但必须由你确认的建议。用户偏好保留至删除；知识库业务事实默认保留指定天数。你可以随时暂停、导出或全部清除。',okText:'同意并启用',cancelText:'暂不启用',onOk:()=>action(()=>memoryApi.saveSettings(next),'长期记忆已启用')});
      return;
    }
    await action(()=>memoryApi.saveSettings(next),next.enabled?'保留策略已更新':'长期记忆已暂停');
  };
  const exportJson=async()=>{
    try{
      const values=await memoryApi.export();
      const url=URL.createObjectURL(new Blob([JSON.stringify(values,null,2)],{type:'application/json'}));
      const anchor=document.createElement('a');anchor.href=url;anchor.download=`modelrag-memories-${new Date().toISOString().slice(0,10)}.json`;anchor.click();URL.revokeObjectURL(url);
    }catch(error){message.error(error instanceof Error?error.message:'导出失败')}
  };

  return <section className="memory-page">
    <div className="eyebrow">USER CONTROL / CONSENT FIRST</div>
    <h1>记忆由你决定。</h1>
    <div className="memory-grid">
      <Card className="memory-policy" title="用途与保留策略">
        <Typography.Paragraph>长期记忆只用于延续你的稳定偏好和已确认事实。模型建议默认处于待确认状态，不会直接进入回答上下文。</Typography.Paragraph>
        <Alert type={settings.enabled?'success':'warning'} showIcon message={settings.enabled?'长期记忆已启用':'长期记忆未启用'} description="暂停后仅保留当前会话的短期上下文，已有记忆不会被自动删除。"/>
        <Space direction="vertical" className="memory-settings">
          <Space><span>启用</span><Switch checked={settings.enabled} onChange={enabled=>void saveSettings({...settings,enabled})}/></Space>
          <Space><span>知识库事实保留</span><InputNumber min={1} max={3650} value={settings.retentionDays} onChange={value=>setSettings({...settings,retentionDays:value||180})}/><span>天</span><Button onClick={()=>void saveSettings(settings)}>保存</Button></Space>
        </Space>
        <div className="memory-policy-notes"><b>用户偏好</b><span>保留至你删除</span><b>待确认建议</b><span>7 天后过期</span><b>业务事实</b><span>仅限所属知识库</span></div>
      </Card>
      <Card title="治理操作" className="memory-actions">
        <Space wrap><Button type="primary" disabled={!settings.enabled} onClick={()=>edit()}>明确记住</Button><Button onClick={()=>void exportJson()}>导出 JSON</Button><Button danger onClick={()=>Modal.confirm({title:'清除全部长期记忆？',content:'此操作会撤销当前用户的全部长期记忆，并保留审计记录。',okText:'全部清除',okButtonProps:{danger:true},onOk:()=>action(()=>memoryApi.clear(),'长期记忆已全部清除')})}>全部清除</Button></Space>
        <Typography.Paragraph type="secondary">确认、拒绝、修改、暂停、恢复、撤销和导出均只作用于当前登录用户。</Typography.Paragraph>
      </Card>
    </div>
    <Card title={`记忆清单（${items.length}）`} className="memory-list">
      <Table loading={loading} rowKey="id" dataSource={items} pagination={{pageSize:10}} columns={[
        {title:'状态',dataIndex:'status',width:110,render:(value:string)=><Tag color={value==='ACTIVE'?'green':value==='PAUSED'?'default':'gold'}>{value==='ACTIVE'?'已确认':value==='PAUSED'?'已暂停':'待确认'}</Tag>},
        {title:'范围',width:160,render:(_:unknown,item:MemoryRecord)=>item.datasetId?(datasets.find(row=>row.id===item.datasetId)?.name||`知识库 ${item.datasetId}`):'用户全局'},
        {title:'类型',dataIndex:'type',width:130,render:(value:string)=>typeOptions.find(item=>item.value===value)?.label||value},
        {title:'内容',dataIndex:'content'},
        {title:'到期',dataIndex:'expiresAt',width:160,render:(value?:string)=>value?new Date(value).toLocaleDateString():'用户删除时'},
        {title:'操作',width:290,render:(_:unknown,item:MemoryRecord)=><Space wrap size={4}>
          {item.status==='PENDING_CONFIRMATION'&&<><Button size="small" type="primary" onClick={()=>void action(()=>memoryApi.confirm(item.id),'记忆已确认')}>确认</Button><Button size="small" onClick={()=>void action(()=>memoryApi.reject(item.id),'建议已拒绝')}>拒绝</Button></>}
          {item.status==='ACTIVE'&&<Button size="small" onClick={()=>void action(()=>memoryApi.pause(item.id),'记忆已暂停')}>暂停</Button>}
          {item.status==='PAUSED'&&<Button size="small" onClick={()=>void action(()=>memoryApi.resume(item.id),'记忆已恢复')}>恢复</Button>}
          {item.status!=='PAUSED'&&<Button size="small" onClick={()=>edit(item)}>修改</Button>}
          <Button size="small" danger onClick={()=>void action(()=>memoryApi.remove(item.id),'记忆已撤销')}>撤销</Button>
        </Space>}
      ]}/>
    </Card>
    <Modal open={open} title={editing?'修改长期记忆':'明确记住'} okText="保存" onOk={()=>form.submit()} onCancel={()=>{setOpen(false);setEditing(undefined);form.resetFields()}} destroyOnClose>
      <Form form={form} layout="vertical" onFinish={save} initialValues={{type:'PREFERENCE'}}>
        <Form.Item name="type" label="类型" rules={[{required:true}]}><Select options={typeOptions}/></Form.Item>
        <Form.Item name="datasetId" label="所属知识库（业务事实必填）"><Select allowClear options={datasets.map(item=>({value:item.id,label:item.name}))}/></Form.Item>
        <Form.Item name="memoryKey" label="稳定键（可选）"><Input maxLength={200} placeholder="例如 answer-style"/></Form.Item>
        <Form.Item name="content" label="记忆内容" rules={[{required:true,whitespace:true},{max:2000}]}><Input.TextArea rows={4} showCount maxLength={2000}/></Form.Item>
      </Form>
    </Modal>
  </section>;
}
