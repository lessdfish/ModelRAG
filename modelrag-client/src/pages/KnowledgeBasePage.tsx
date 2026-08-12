import {useEffect,useMemo,useState} from 'react';
import {Button,Card,Descriptions,Drawer,Form,Input,InputNumber,Popconfirm,Select,Space,Table,Tag,Typography,Upload,message} from 'antd';
import {DeleteOutlined,EditOutlined,EyeOutlined,InboxOutlined,PlusOutlined,ReloadOutlined} from '@ant-design/icons';
import {adminSecurityApi,kbApi,type KnowledgeBasePayload} from '../api/api';
import {useSSE} from '../hooks/useSSE';
import type {Chunk,Dataset,Document,SecurityUser} from '../types';

export function KnowledgeBasePage({admin}:{admin:boolean}){
  const [items,setItems]=useState<Dataset[]>([]);
  const [active,setActive]=useState<Dataset>();
  const [docs,setDocs]=useState<Document[]>([]);
  const [users,setUsers]=useState<SecurityUser[]>([]);
  const [grantUser,setGrantUser]=useState<string>();
  const [open,setOpen]=useState(false);
  const [editing,setEditing]=useState(false);
  const [indexingId,setIndexingId]=useState<number>();
  const [chunks,setChunks]=useState<Chunk[]>([]);
  const [chunkOpen,setChunkOpen]=useState(false);
  const [form]=Form.useForm();
  const stream=useSSE(indexingId?`/api/v1/knowledge-bases/${active?.id}/documents/${indexingId}/stream`:undefined);

  const load=async()=>{
    const [kbs,userRows]=await Promise.all([
      kbApi.list(),
      admin?adminSecurityApi.users().catch(()=>[]):Promise.resolve([])
    ]);
    setItems(kbs);
    setUsers(userRows);
  };

  const refresh=async()=>{
    if(!active)return;
    setDocs(await kbApi.documents(active.id));
    if(admin)setUsers(await adminSecurityApi.users().catch(()=>[]));
  };

  useEffect(()=>{load().catch(()=>undefined)},[]);
  useEffect(()=>{if(stream.events.length)refresh()},[stream.events.length]);

  const select=async(dataset:Dataset)=>{
    setActive(dataset);
    setIndexingId(undefined);
    setGrantUser(undefined);
    setDocs(await kbApi.documents(dataset.id));
  };

  const openCreate=()=>{
    setEditing(false);
    form.setFieldsValue({chunkSize:512,chunkOverlap:64,topK:5,threshold:.7});
    setOpen(true);
  };

  const openEdit=()=>{
    if(!active)return;
    setEditing(true);
    form.setFieldsValue(active);
    setOpen(true);
  };

  const submit=async(values:KnowledgeBasePayload)=>{
    const dataset=editing&&active?await kbApi.update(active.id,values):await kbApi.create(values);
    setOpen(false);
    await load();
    await select(dataset);
    message.success(editing?'知识库已保存；切分参数变化时会自动清缓存并重建索引':'知识库已创建，请继续上传文档');
  };

  const removeDataset=async()=>{
    if(!active)return;
    await kbApi.remove(active.id);
    setActive(undefined);
    setDocs([]);
    await load();
    message.success('知识库、索引与问答缓存已删除');
  };

  const removeDocument=async(document:Document)=>{
    if(!active)return;
    await kbApi.removeDocument(active.id,document.id);
    await refresh();
    message.success('文档已删除，历史问答缓存已失效');
  };

  const showChunks=async(document:Document)=>{
    if(!active)return;
    setChunks(await kbApi.chunks(active.id,document.id));
    setChunkOpen(true);
  };

  const grantActiveDataset=async()=>{
    if(!active)return message.warning('请先选择知识库');
    if(!grantUser)return message.warning('请选择用户');
    await adminSecurityApi.grantDataset(grantUser,active.id);
    setGrantUser(undefined);
    await refresh();
    message.success('知识库已开放给该用户');
  };

  const revokeActiveDataset=async(userId:string)=>{
    if(!active)return;
    await adminSecurityApi.revokeDataset(userId,active.id);
    await refresh();
    message.success('已撤销该用户的知识库权限');
  };

  const directUsers=useMemo(()=>active?users.filter(user=>user.datasetIds?.includes(active.id)):[],[active,users]);
  const grantOptions=useMemo(()=>users
    .filter(user=>!user.roles?.includes('ADMIN'))
    .filter(user=>!active||!user.datasetIds?.includes(active.id))
    .map(user=>({value:user.userId,label:`${user.displayName||user.userId}（${user.userId}）`})),[active,users]);

  return <section className="knowledge-page">
    <div className="eyebrow">LIBRARY / INGESTION</div>
    <h1>{admin?'知识库与文档索引':'知识库'}</h1>
    <div className="kb-grid">
      <Card size="small" title="知识库" extra={admin?<Button icon={<PlusOutlined/>} onClick={openCreate}>新建</Button>:undefined}>
        <Table size="small" rowKey="id" dataSource={items} pagination={{pageSize:8,size:'small'}} onRow={row=>({onClick:()=>select(row)})} columns={[
          {title:'名称',dataIndex:'name'},
          {title:'分块',render:(_,row)=>`${row.chunkSize}/${row.chunkOverlap}`},
          {title:'检索',render:(_,row)=>`Top ${row.topK} / ≥ ${row.threshold}`}
        ]}/>
      </Card>
      <Card className="kb-detail" size="small" title={active?active.name:'选择一个知识库'}>
        {active&&<>
          <Descriptions size="small" column={{xs:1,sm:3}} items={[
            {key:'description',label:'说明',children:active.description||'未填写说明'},
            {key:'chunk',label:'切分参数',children:`每块 ${active.chunkSize} / 重叠 ${active.chunkOverlap}`},
            {key:'retrieval',label:'检索参数',children:`Top ${active.topK} / 阈值 ${active.threshold}`},
            {key:'revision',label:'知识库版本',children:`rev ${active.revision}`},
            {key:'documents',label:'当前内容',children:`${docs.length} 份文档，${docs.reduce((sum,item)=>sum+item.chunkCount,0)} 个分块`}
          ]}/>
          {admin&&<>
            <Space className="kb-actions" wrap>
              <Button icon={<EditOutlined/>} onClick={openEdit}>编辑知识库</Button>
              <Button icon={<ReloadOutlined/>} onClick={async()=>{const result=await kbApi.rebuild(active.id);message.success(`已清缓存并提交 ${result.documents} 份文档重建；${result.requeued} 条旧任务已重新入队`)}}>重建索引</Button>
              <Popconfirm title="将删除此知识库及全部文档，确认吗？" onConfirm={removeDataset}><Button danger icon={<DeleteOutlined/>}>删除知识库</Button></Popconfirm>
            </Space>
            <Card size="small" type="inner" title="开放给哪些用户" className="kb-acl-card" extra={<Space>
              <Select size="small" style={{minWidth:240}} placeholder="选择普通用户" value={grantUser} onChange={setGrantUser} options={grantOptions}/>
              <Button size="small" type="primary" onClick={grantActiveDataset}>开放权限</Button>
            </Space>}>
              <Table size="small" rowKey="userId" dataSource={directUsers} pagination={false} locale={{emptyText:'尚未开放给普通用户'}} columns={[
                {title:'直属用户',render:(_,row)=><Space><span>{row.displayName||row.userId}</span><Typography.Text type="secondary">{row.userId}</Typography.Text></Space>},
                {title:'角色',width:180,render:(_,row)=><Space wrap>{row.roles?.map(role=><Tag key={role}>{role}</Tag>)}</Space>},
                {title:'操作',width:90,render:(_,row)=><Popconfirm title="撤销该用户访问此知识库？" onConfirm={()=>revokeActiveDataset(row.userId)}><Button size="small" danger>撤销</Button></Popconfirm>}
              ]}/>
            </Card>
            <Upload.Dragger className="compact-upload" name="file" accept=".pdf,.docx,.md,.txt" customRequest={async option=>{
              try{
                const document=await kbApi.upload(active.id,option.file as File);
                setIndexingId(document.id);
                await refresh();
                message.success('已提交索引，历史问答缓存已失效');
                option.onSuccess?.({});
              }catch(error){option.onError?.(error as Error);}
            }}>
              <p className="ant-upload-drag-icon"><InboxOutlined/></p>
              <p>拖入 PDF、DOCX、MD 或 TXT 文件</p>
            </Upload.Dragger>
          </>}
          {indexingId&&<p className="indexing-status">实时索引状态：<Tag color={stream.connected?'blue':'default'}>{stream.events[stream.events.length-1]?.type||'正在连接'}</Tag></p>}
          <Table className="document-table" size="small" rowKey="id" dataSource={docs} pagination={{pageSize:5,size:'small'}} scroll={{y:220}} columns={[
            {title:'文件',dataIndex:'fileName',ellipsis:true},
            {title:'分块',dataIndex:'chunkCount',width:62},
            {title:'状态',width:82,render:(_,row)=><Tag color={row.status==='READY'?'green':row.status==='FAILED'?'red':'gold'}>{row.status}</Tag>},
            {title:'操作',width:admin?170:100,render:(_,row)=><Space size="small"><Button size="small" icon={<EyeOutlined/>} onClick={()=>showChunks(row)}>查看分块</Button>{admin&&<Popconfirm title="确认删除该文档？" onConfirm={()=>removeDocument(row)}><Button size="small" danger>删除</Button></Popconfirm>}</Space>}
          ]}/>
        </>}
      </Card>
    </div>
    <Drawer open={open} title={editing?'编辑知识库':'新建知识库'} onClose={()=>setOpen(false)}>
      <Form form={form} onFinish={submit} layout="vertical">
        <Form.Item name="name" label="名称" rules={[{required:true,message:'请输入名称'}]}><Input/></Form.Item>
        <Form.Item name="description" label="说明"><Input.TextArea/></Form.Item>
        <Space align="start" wrap>
          <Form.Item name="chunkSize" label="单块长度" rules={[{required:true,message:'请输入单块长度'}]}><InputNumber min={128} max={4096} step={64}/></Form.Item>
          <Form.Item name="chunkOverlap" label="重叠长度" rules={[{required:true,message:'请输入重叠长度'}]}><InputNumber min={0} max={2048} step={16}/></Form.Item>
          <Form.Item name="topK" label="问答 TopK" rules={[{required:true,message:'请输入 TopK'}]}><InputNumber min={1} max={20}/></Form.Item>
          <Form.Item name="threshold" label="相似度阈值" rules={[{required:true,message:'请输入阈值'}]}><InputNumber min={0} max={1} step={0.05}/></Form.Item>
        </Space>
        <Typography.Text type="secondary">修改切分参数会清理旧问答缓存，并按新参数重建文档分块、向量和 BM25 索引；知识库版本会递增，旧版本回答缓存不会再命中。</Typography.Text>
        <br/><br/>
        <Button htmlType="submit" type="primary">{editing?'保存':'创建'}</Button>
      </Form>
    </Drawer>
    <Drawer open={chunkOpen} title={`文档分块（${chunks.length}）`} onClose={()=>setChunkOpen(false)} width={720}>
      <Table size="small" rowKey="id" dataSource={chunks} pagination={{pageSize:5}} columns={[
        {title:'序号',dataIndex:'index',width:70},
        {title:'内容',dataIndex:'content',render:value=><span style={{whiteSpace:'pre-wrap'}}>{value}</span>},
        {title:'元数据',dataIndex:'metadata',render:value=>Object.entries(value).map(([key,item])=>`${key}: ${item}`).join(' · ')}
      ]}/>
    </Drawer>
  </section>;
}
