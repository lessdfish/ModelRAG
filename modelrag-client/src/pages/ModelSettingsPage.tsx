import {useEffect,useState} from 'react';
import {Button,Card,Form,Input,Select,Space,Switch,Table,Tag,message,Typography} from 'antd';
import {CheckCircleOutlined,DeleteOutlined,ReloadOutlined} from '@ant-design/icons';
import {modelConfigApi} from '../api/api';
import type {ModelConfig} from '../types';

type ModelForm={modelType:string;provider:string;modelName:string;baseUrl?:string;secretId?:string;apiKey?:string;enabled:boolean};

export function ModelSettingsPage(){
  const [configs,setConfigs]=useState<ModelConfig[]>([]);
  const [loading,setLoading]=useState(false);
  const [form]=Form.useForm<ModelForm>();
  const reload=async()=>{setLoading(true);try{setConfigs(await modelConfigApi.list())}catch(error){message.error(error instanceof Error?error.message:'模型配置读取失败')}finally{setLoading(false)}};
  useEffect(()=>{void reload()},[]);
  const save=async(values:ModelForm)=>{
    try{await modelConfigApi.save({...values,modelType:'CHAT'});form.resetFields();await reload();message.success(values.enabled===false?'模型配置已保存（未启用）':'模型配置已保存并通过同步/流式能力探测')}catch(error){message.error(error instanceof Error?error.message:'模型配置保存或能力探测失败')}
  };
  const validate=async(id:string)=>{try{const result=await modelConfigApi.validate(id);message[result.status==='READY'?'success':'warning'](result.message);await reload()}catch(error){message.error(error instanceof Error?error.message:'验证失败')}};
  const revoke=async(id:string)=>{try{await modelConfigApi.revoke(id);await reload();message.success('模型配置已撤销')}catch(error){message.error(error instanceof Error?error.message:'撤销失败')}};
  return <section>
    <div className="eyebrow">PERSONAL MODEL / BYOK</div>
    <h1>我的模型</h1>
    <Typography.Paragraph type="secondary">配置后端调用的个人模型。API Key 只在服务端加密保存，页面只显示掩码；云端地址必须使用 HTTPS，本地 Ollama 仅允许回环地址。</Typography.Paragraph>
    <Card title="添加或更新聊天模型" className="monitor">
      <Form form={form} layout="vertical" onFinish={save} initialValues={{modelType:'CHAT',provider:'ollama',enabled:true}}>
        <Space wrap align="start">
          <Form.Item name="provider" label="Provider" rules={[{required:true,message:'请选择 provider'}]}><Select style={{width:180}} options={[
            {value:'ollama',label:'Ollama（本地）'},{value:'openai',label:'OpenAI'},{value:'openai-compatible',label:'OpenAI-compatible'},{value:'anthropic',label:'Anthropic'},{value:'gemini',label:'Gemini / Google'},{value:'deepseek',label:'DeepSeek'}
          ]}/></Form.Item>
          <Form.Item name="modelName" label="模型名" rules={[{required:true,message:'请输入模型名'}]}><Input style={{width:230}} placeholder="例如 qwen3:8b"/></Form.Item>
          <Form.Item name="baseUrl" label="Base URL"><Input style={{width:300}} placeholder="Ollama 默认 http://127.0.0.1:11434"/></Form.Item>
        </Space>
        <Space wrap align="start">
          <Form.Item name="apiKey" label="API Key"><Input.Password style={{width:300}} placeholder="留空则保留已保存的 Key" autoComplete="new-password"/></Form.Item>
          <Form.Item name="secretId" label="外部 Secret ID"><Input style={{width:220}} placeholder="可选：外部密钥引用"/></Form.Item>
          <Form.Item name="enabled" label="启用" valuePropName="checked"><Switch checkedChildren="启用" unCheckedChildren="停用"/></Form.Item>
          <Form.Item label=" "><Button type="primary" htmlType="submit">保存配置</Button></Form.Item>
        </Space>
      </Form>
    </Card>
    <Card title="已保存配置" className="monitor" extra={<Button icon={<ReloadOutlined/>} onClick={()=>void reload()} loading={loading}>刷新</Button>}>
      <Table rowKey="id" loading={loading} dataSource={configs} pagination={false} locale={{emptyText:'还没有个人模型配置'}} columns={[
        {title:'Provider / 模型',render:(_:unknown,row:ModelConfig)=><Space direction="vertical" size={0}><span>{row.provider} · {row.modelName}</span><small>{row.baseUrl||'默认地址'}</small></Space>},
        {title:'Key',render:(_:unknown,row:ModelConfig)=>row.hasApiKey?<Tag color="green">{row.apiKeyHint||'已保存'}</Tag>:<Tag>未配置</Tag>},
        {title:'状态',dataIndex:'capabilityStatus',render:(value:string)=><Tag color={value==='READY'?'green':value==='FAILED'?'red':'gold'}>{value}</Tag>},
        {title:'启用',render:(_:unknown,row:ModelConfig)=><Tag color={row.enabled?'green':'default'}>{row.enabled?'是':'否'}</Tag>},
        {title:'操作',render:(_:unknown,row:ModelConfig)=><Space><Button size="small" icon={<CheckCircleOutlined/>} onClick={()=>void validate(row.id)}>验证</Button><Button danger size="small" icon={<DeleteOutlined/>} onClick={()=>void revoke(row.id)}>撤销</Button></Space>}
      ]}/>
    </Card>
  </section>;
}
