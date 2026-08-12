import React,{Suspense,lazy,useEffect,useState} from 'react';
import {createRoot} from 'react-dom/client';
import {App,Button,Card,ConfigProvider,Form,Input,Layout,Menu,Modal,Space,Spin,Tag,Typography,message} from 'antd';
import {ApiOutlined,AuditOutlined,BookOutlined,DashboardOutlined,ExperimentOutlined,MessageOutlined,SafetyCertificateOutlined} from '@ant-design/icons';
import {authApi} from './api/api';
import {AUTH_TOKEN_KEY} from './api/client';
import type {AuthUser} from './types';
import './style.css';

const ChatPage=lazy(()=>import('./pages/ChatPage').then(m=>({default:m.ChatPage})));
const KnowledgeBasePage=lazy(()=>import('./pages/KnowledgeBasePage').then(m=>({default:m.KnowledgeBasePage})));
const AuditPage=lazy(()=>import('./pages/AuditPage').then(m=>({default:m.AuditPage})));
const MonitorPage=lazy(()=>import('./pages/MonitorPage').then(m=>({default:m.MonitorPage})));
const EvalPage=lazy(()=>import('./pages/EvalPage').then(m=>({default:m.EvalPage})));
const ToolPage=lazy(()=>import('./pages/ToolPage').then(m=>({default:m.ToolPage})));
const SecurityPage=lazy(()=>import('./pages/SecurityPage').then(m=>({default:m.SecurityPage})));

type ViewKey='chat'|'kb'|'tools'|'audit'|'security'|'monitor'|'eval';

function Shell(){
  const [view,setView]=useState<ViewKey>('chat');
  const [user,setUser]=useState<AuthUser>();
  const [checking,setChecking]=useState(true);
  const [evalDatasetId,setEvalDatasetId]=useState<number>();

  useEffect(()=>{
    const token=localStorage.getItem(AUTH_TOKEN_KEY);
    if(!token){setChecking(false);return;}
    authApi.me().then(setUser).catch(()=>localStorage.removeItem(AUTH_TOKEN_KEY)).finally(()=>setChecking(false));
  },[]);

  const login=async(values:{username:string;password:string})=>{
    const result=await authApi.login(values.username,values.password);
    localStorage.setItem(AUTH_TOKEN_KEY,result.token);
    setUser(result.user);
    message.success('登录成功');
  };
  const register=async(values:{username:string;password:string;displayName?:string})=>{
    const result=await authApi.register(values.username,values.password,values.displayName);
    localStorage.setItem(AUTH_TOKEN_KEY,result.token);
    setUser(result.user);
    message.success('注册成功，已按普通用户登录');
  };
  const logout=()=>{
    localStorage.removeItem(AUTH_TOKEN_KEY);
    setUser(undefined);
    setView('chat');
  };

  if(checking)return <Theme><Card><Spin/> 正在检查登录态...</Card></Theme>;
  if(!user)return <Theme><LoginPage onLogin={login} onRegister={register}/></Theme>;
  const admin=isAdmin(user.roles);
  const approver=isApprover(user.roles);
  const views:Record<ViewKey,React.ReactNode>={
    chat:<ChatPage currentUserId={user.id}/>,
    kb:<KnowledgeBasePage admin={admin}/>,
    tools:<ToolPage admin={admin}/>,
    audit:<AuditPage admin={admin} currentUserId={user.id} onOpenEval={datasetId=>{setEvalDatasetId(datasetId);setView('eval')}}/>,
    security:<SecurityPage/>,
    monitor:<MonitorPage/>,
    eval:<EvalPage initialDatasetId={evalDatasetId}/>
  };

  return <Theme>
    <Layout className="shell">
      <Layout.Sider className="rail" width={216}>
        <div className="brand">MODEL<span>RAG</span><small>KNOWLEDGE OPS</small></div>
        <Menu theme="dark" selectedKeys={[view]} onClick={e=>setView(e.key as ViewKey)} items={[
          {key:'chat',icon:<MessageOutlined/>,label:'对话工作台'},
          {key:'kb',icon:<BookOutlined/>,label:'知识库'},
          {key:'tools',icon:<ApiOutlined/>,label:admin?'工具':'可用工具'},
          ...(approver&&!admin?[{key:'audit',icon:<AuditOutlined/>,label:'审批待办'}]:[]),
          ...(admin?[
            {key:'audit',icon:<AuditOutlined/>,label:'审计与审批'},
            {key:'security',icon:<SafetyCertificateOutlined/>,label:'权限'},
            {key:'monitor',icon:<DashboardOutlined/>,label:'监控'},
            {key:'eval',icon:<ExperimentOutlined/>,label:'评测'}
          ]:[])
        ].map(item=>item.key==='chat'?{...item,label:'对话'}:item)}/>
        <div className="rail-foot">v1.0 · 本地模型</div>
      </Layout.Sider>
      <Layout>
        <Layout.Header className="top">
          <Typography.Text>企业知识 · 可溯源回答</Typography.Text>
          <Space>
            <Tag color="green">{user.id}</Tag>
            <Typography.Text type="secondary">{roleText(user.roles)}</Typography.Text>
            <Button type="text" onClick={logout}>退出</Button>
          </Space>
        </Layout.Header>
        <Layout.Content className="content"><Suspense fallback={<Card><Spin/> 页面加载中...</Card>}>{views[view]}</Suspense></Layout.Content>
      </Layout>
    </Layout>
  </Theme>;
}

function LoginPage({onLogin,onRegister}:{onLogin:(values:{username:string;password:string})=>Promise<void>;onRegister:(values:{username:string;password:string;displayName?:string})=>Promise<void>}){
  const [loading,setLoading]=useState(false);
  const [registerOpen,setRegisterOpen]=useState(false);
  const [registering,setRegistering]=useState(false);
  const submit=async(values:{username:string;password:string})=>{
    setLoading(true);
    try{await onLogin(values)}catch(error){message.error(error instanceof Error?error.message:'登录失败')}finally{setLoading(false)}
  };
  const submitRegister=async(values:{username:string;password:string;displayName?:string})=>{
    setRegistering(true);
    try{await onRegister(values)}catch(error){message.error(error instanceof Error?error.message:'注册失败')}finally{setRegistering(false)}
  };
  return <section className="login-shell">
    <Card className="login-card">
      <div className="eyebrow">LOCAL AUTH / MODEL RAG</div>
      <h1>进入知识库控制台。</h1>
      <Typography.Paragraph type="secondary">本地默认账号可通过环境变量修改：`MODELRAG_LOCAL_ADMIN_USERNAME` / `MODELRAG_LOCAL_ADMIN_PASSWORD`。</Typography.Paragraph>
      <Form layout="vertical" onFinish={submit} initialValues={{username:'admin',password:'modelrag'}}>
        <Form.Item name="username" label="账号" rules={[{required:true,message:'请输入账号'}]}><Input autoComplete="username"/></Form.Item>
        <Form.Item name="password" label="密码" rules={[{required:true,message:'请输入密码'}]}><Input.Password autoComplete="current-password"/></Form.Item>
        <Button type="primary" htmlType="submit" loading={loading} block>登录</Button>
        <Button style={{marginTop:10}} block onClick={()=>setRegisterOpen(true)}>注册普通用户</Button>
      </Form>
      <Modal open={registerOpen} title="注册普通用户" okText="注册并登录" confirmLoading={registering} onOk={()=>document.getElementById('register-submit')?.click()} onCancel={()=>setRegisterOpen(false)} destroyOnClose>
        <Form layout="vertical" onFinish={submitRegister}>
          <Form.Item name="username" label="账号" rules={[{required:true,message:'请输入账号'}]}><Input placeholder="例如 lisi"/></Form.Item>
          <Form.Item name="displayName" label="显示名"><Input placeholder="例如 李四"/></Form.Item>
          <Form.Item name="password" label="密码" rules={[{required:true,message:'请输入密码'},{min:4,message:'至少 4 位'}]}><Input.Password/></Form.Item>
          <button id="register-submit" type="submit" style={{display:'none'}}/>
          <Typography.Text type="secondary">注册后默认角色为 USER，只能访问管理员授权的知识库和可用工具。当前本地环境会自动授予已有知识库 READ 权限，管理员可在“权限”页调整。</Typography.Text>
        </Form>
      </Modal>
    </Card>
  </section>;
}

function Theme({children}:{children:React.ReactNode}){
  return <ConfigProvider theme={{token:{colorPrimary:'#e85d3f',fontFamily:'Noto Serif SC, serif',borderRadius:5}}}><App>{children}</App></ConfigProvider>;
}

function roleText(value:AuthUser['roles']){
  return Array.from(value as Iterable<string>).join(' / ');
}

function isAdmin(value:AuthUser['roles']){
  return Array.from(value as Iterable<string>).includes('ADMIN');
}

function isApprover(value:AuthUser['roles']){
  return Array.from(value as Iterable<string>).includes('APPROVER')||isAdmin(value);
}

createRoot(document.getElementById('root')!).render(<Shell/>);
