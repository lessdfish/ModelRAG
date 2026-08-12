import {useEffect,useMemo,useState} from 'react';
import {Button,Card,Checkbox,Form,Input,Popconfirm,Select,Space,Switch,Table,Tag,Typography,message} from 'antd';
import {DeleteOutlined,PlusOutlined,ReloadOutlined,SaveOutlined,UserAddOutlined} from '@ant-design/icons';
import client from '../api/client';
import {kbApi} from '../api/api';
import type {ApiResponse,Dataset} from '../types';

type SecurityUser={userId:string;displayName?:string;enabled:boolean;roles:string[];datasetIds:number[]};
type UserForm={userId:string;displayName?:string;password?:string;enabled:boolean;roles:string[]};
const unwrap=<T,>(promise:Promise<{data:ApiResponse<T>}>)=>promise.then(response=>response.data.data);

export function SecurityPage(){
  const [users,setUsers]=useState<SecurityUser[]>([]);
  const [datasets,setDatasets]=useState<Dataset[]>([]);
  const [active,setActive]=useState<SecurityUser>();
  const [loading,setLoading]=useState(false);
  const [saving,setSaving]=useState(false);
  const [grantDataset,setGrantDataset]=useState<number>();
  const [grantPermission,setGrantPermission]=useState('READ');
  const [form]=Form.useForm<UserForm>();

  const load=async()=>{
    setLoading(true);
    try{
      const [userRows,kbs]=await Promise.all([
        unwrap<SecurityUser[]>(client.get('/admin/security/users')),
        kbApi.list()
      ]);
      setUsers(userRows);
      setDatasets(kbs);
      const next=userRows.find(item=>item.userId===active?.userId)||userRows[0];
      select(next);
    }catch(error){message.error(error instanceof Error?error.message:'权限数据加载失败')}
    finally{setLoading(false)}
  };

  useEffect(()=>{load().catch(()=>undefined)},[]);

  const select=(user?:SecurityUser)=>{
    setActive(user);
    if(!user){form.resetFields();return;}
    form.setFieldsValue({userId:user.userId,displayName:user.displayName,password:'',enabled:user.enabled!==false,roles:user.roles?.length?user.roles:['USER']});
  };

  const create=()=>{
    const draft={userId:'',displayName:'',enabled:true,roles:['USER'],datasetIds:[]} as SecurityUser;
    setActive(draft);
    form.setFieldsValue({userId:'',displayName:'',password:'',enabled:true,roles:['USER']});
  };

  const save=async(values:UserForm)=>{
    setSaving(true);
    try{
      const payload={...values,password:values.password?.trim()||undefined,roles:values.roles?.length?values.roles:['USER']};
      const saved=await unwrap<SecurityUser>(client.post('/admin/security/users',payload));
      message.success('用户权限已保存');
      setActive(saved);
      await load();
    }catch(error){message.error(error instanceof Error?error.message:'保存失败')}
    finally{setSaving(false)}
  };

  const grant=async()=>{
    if(!active?.userId)return message.warning('请先选择用户');
    if(!grantDataset)return message.warning('请选择知识库');
    await unwrap(client.post(`/admin/security/users/${encodeURIComponent(active.userId)}/datasets/${grantDataset}`,{permission:grantPermission}));
    message.success('知识库授权已更新');
    await load();
  };

  const revoke=async(datasetId:number)=>{
    if(!active?.userId)return;
    await unwrap(client.delete(`/admin/security/users/${encodeURIComponent(active.userId)}/datasets/${datasetId}`));
    message.success('知识库授权已撤销');
    await load();
  };

  const datasetName=useMemo(()=>new Map(datasets.map(item=>[item.id,item.name])),[datasets]);

  return <section className="security-page">
    <div className="eyebrow">SECURITY / ACL</div>
    <h1>用户与知识库权限</h1>
    <div className="security-grid">
      <Card size="small" title="用户账号" extra={<Space><Button icon={<ReloadOutlined/>} onClick={load}>刷新</Button><Button type="primary" icon={<UserAddOutlined/>} onClick={create}>新用户</Button></Space>}>
        <Table size="small" rowKey="userId" loading={loading} dataSource={users} pagination={{pageSize:8,size:'small'}} onRow={row=>({onClick:()=>select(row)})} columns={[
          {title:'用户',render:(_,row)=><Space direction="vertical" size={0}><b>{row.displayName||row.userId}</b><Typography.Text type="secondary">{row.userId}</Typography.Text></Space>},
          {title:'角色',width:190,render:(_,row)=><Space wrap>{row.roles?.map(role=><Tag key={role} color={role==='ADMIN'?'red':role==='APPROVER'?'gold':'default'}>{role}</Tag>)}</Space>},
          {title:'知识库',width:90,render:(_,row)=>row.roles?.includes('ADMIN')?'全部':row.datasetIds?.length||0},
          {title:'状态',width:80,render:(_,row)=><Tag color={row.enabled===false?'red':'green'}>{row.enabled===false?'禁用':'启用'}</Tag>}
        ]}/>
      </Card>
      <Card size="small" title={active?.userId?'编辑用户':'创建用户'} className="security-detail">
        <Form form={form} layout="vertical" onFinish={save} initialValues={{enabled:true,roles:['USER']}}>
          <Space align="start" wrap>
            <Form.Item name="userId" label="用户 ID" rules={[{required:true,message:'请输入用户 ID'}]}><Input disabled={!!active?.userId} placeholder="例如 zhangsan"/></Form.Item>
            <Form.Item name="displayName" label="显示名"><Input placeholder="用于后台识别"/></Form.Item>
            <Form.Item name="password" label={active?.userId?'重置密码':'初始密码'} rules={active?.userId?[]:[{required:true,message:'新用户必须设置初始密码'}]}><Input.Password placeholder={active?.userId?'留空表示不修改':'至少输入一个初始密码'}/></Form.Item>
            <Form.Item name="enabled" label="账号状态" valuePropName="checked"><Switch checkedChildren="启用" unCheckedChildren="禁用"/></Form.Item>
          </Space>
          <Form.Item name="roles" label="角色"><Checkbox.Group options={[{label:'普通用户 USER',value:'USER'},{label:'审批人 APPROVER',value:'APPROVER'},{label:'管理员 ADMIN',value:'ADMIN'}]}/></Form.Item>
          <Typography.Paragraph type="secondary">ADMIN 可访问全部知识库；普通用户只会检索下方授权的知识库。审批人仍需拥有对应知识库权限才能处理该范围内的审批。</Typography.Paragraph>
          <Button type="primary" icon={<SaveOutlined/>} htmlType="submit" loading={saving}>保存用户</Button>
        </Form>
        <Card size="small" type="inner" title="知识库授权" className="acl-card" extra={<Button icon={<PlusOutlined/>} onClick={grant}>授权</Button>}>
          <Space wrap className="acl-toolbar">
            <Select style={{minWidth:240}} placeholder="选择知识库" value={grantDataset} onChange={setGrantDataset} options={datasets.map(item=>({value:item.id,label:item.name}))}/>
            <Select style={{width:120}} value={grantPermission} onChange={setGrantPermission} options={['READ','WRITE','ADMIN'].map(value=>({value,label:value}))}/>
          </Space>
          <Table size="small" rowKey={value=>value} dataSource={active?.datasetIds||[]} pagination={false} columns={[
            {title:'知识库',render:value=><span>#{value} {datasetName.get(value)||'未知知识库'}</span>},
            {title:'操作',width:90,render:value=><Popconfirm title="撤销该知识库授权？" onConfirm={()=>revoke(value)}><Button size="small" danger icon={<DeleteOutlined/>}>撤销</Button></Popconfirm>}
          ]}/>
        </Card>
      </Card>
    </div>
  </section>;
}
