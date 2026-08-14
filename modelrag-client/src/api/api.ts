import client from './client';
import type {
  AgentResult,
  AssistantAnswer,
  AgentStepTrace,
  ApiResponse,
  Approval,
  AuthUser,
  AutoQaResult,
  Chunk,
  ContextPolicy,
  Conversation,
  ConversationEntry,
  Dataset,
  Document,
  EvalComparison,
  EvalItem,
  EvalReport,
  EvalTask,
  Feedback,
  IntentNode,
  LoginResult,
  MemoryRecord,
  MemorySettings,
  ModelHealth,
  ModelConfig,
  QaAudit,
  QaResult,
  RetrievalReplay,
  SecurityUser,
  TokenUsage,
  ToolDefinition,
  ToolTrace
} from '../types';

const data = <T>(p: Promise<{ data: ApiResponse<T> }>) => p.then(r => r.data.data);

export type KnowledgeBasePayload = {
  name: string;
  description?: string;
  chunkSize?: number;
  chunkOverlap?: number;
  topK?: number;
  threshold?: number;
};

export const authApi = {
  login: (username: string, password: string) => data<LoginResult>(client.post('/auth/login', { username, password })),
  register: (username: string, password: string, displayName?: string) => data<LoginResult>(client.post('/auth/register', { username, password, displayName })),
  refresh: (refreshToken: string) => data<LoginResult>(client.post('/auth/refresh', { refreshToken })),
  logout: (refreshToken: string) => data<void>(client.post('/auth/logout', { refreshToken })),
  me: () => data<AuthUser>(client.get('/auth/me'))
};

export const kbApi = {
  list: () => data<Dataset[]>(client.get('/datasets')),
  create: (body: KnowledgeBasePayload) => data<Dataset>(client.post('/datasets', body)),
  update: (id: number, body: KnowledgeBasePayload) => data<Dataset>(client.put(`/datasets/${id}`, body)),
  remove: (id: number) => data<void>(client.delete(`/datasets/${id}`)),
  rebuild: (id: number) => data<{ documents: number; requeued: number }>(client.post(`/datasets/${id}/rebuild`)),
  documents: (id: number) => data<Document[]>(client.get(`/datasets/${id}/documents`)),
  removeDocument: (_datasetId: number, documentId: number) => data<void>(client.delete(`/documents/${documentId}`)),
  chunks: (datasetId: number, documentId: number) => data<Chunk[]>(client.get(`/datasets/${datasetId}/documents/${documentId}/chunks`)),
  indexStatus: (documentId: number) => data<{ documentId: number; datasetId: number; status: string; error?: string; chunkCount: number }>(client.get(`/documents/${documentId}/index-status`)),
  reindex: (documentId: number) => data<Document>(client.post(`/documents/${documentId}/reindex`)),
  upload: (id: number, file: File) => {
    const form = new FormData();
    form.append('file', file);
    return data<Document>(client.post(`/datasets/${id}/documents`, form));
  }
};

export const qaApi = {
  ask: (datasetId: number, query: string, conversationId?: number) => data<AssistantAnswer>(client.post('/assistant/ask', { datasetId, question: query, conversationId, agent: false })),
  auto: (query: string, conversationId?: number) => data<AutoQaResult>(client.post('/assistant/auto', { question: query, conversationId })),
  feedback: (traceId: string, rating: 'LIKE' | 'DISLIKE', comment = '') => data<Feedback>(client.post('/qa/feedback', { traceId, rating, comment })),
  contextPolicy: () => data<ContextPolicy>(client.get('/qa/context-policy'))
};

export const agentApi = {
  run: (datasetId: number, query: string, conversationId?: number) => data<AssistantAnswer>(client.post('/assistant/ask', { datasetId, question: query, conversationId, agent: true })),
  approvals: () => data<Approval[]>(client.get('/approvals')),
  approve: (approvalId: string, datasetId: number, query: string, approved: boolean, conversationId?: number) =>
    data<AgentResult>(client.post(`/approvals/${approvalId}/${approved ? 'approve' : 'reject'}`, { datasetId, question: query, conversationId })),
  cancel: (executionId: string) => data<void>(client.delete(`/assistant/executions/${encodeURIComponent(executionId)}`)),
};

export const toolApi = {
  list: () => data<ToolDefinition[]>(client.get('/tools')),
  all: () => data<ToolDefinition[]>(client.get('/tools', { params: { includeDisabled: true } })),
  register: (tool: ToolDefinition) => data<ToolDefinition>(client.post('/tools', tool)),
  setEnabled: (name: string, value: boolean, tool?: ToolDefinition) => data<ToolDefinition>(client.put(`/tools/${encodeURIComponent(name)}`, { ...(tool || { name }), name, enabled: value })),
  remove: (name: string) => data<void>(client.delete(`/tools/${encodeURIComponent(name)}`))
};

export const intentApi = {
  list: (datasetId: number) => data<IntentNode[]>(client.get(`/datasets/${datasetId}/intents`)),
  create: (datasetId: number, node: IntentNode) => data<IntentNode>(client.post(`/datasets/${datasetId}/intents`, node)),
  update: (datasetId: number, id: number, node: IntentNode) => data<IntentNode>(client.put(`/datasets/${datasetId}/intents/${id}`, node)),
  remove: (datasetId: number, id: number) => data<void>(client.delete(`/datasets/${datasetId}/intents/${id}`))
};

export const adminApi = {
  qaAudits: () => data<QaAudit[]>(client.get('/admin/qa-audits')),
  approvals: () => data<Approval[]>(client.get('/admin/approvals')),
  feedbacks: () => data<Feedback[]>(client.get('/admin/feedbacks')),
  toolTraces: () => data<ToolTrace[]>(client.get('/tools/traces')),
  agentSteps: () => data<AgentStepTrace[]>(client.get('/admin/agent-steps')),
  replay: (traceId: string) => data<RetrievalReplay>(client.get(`/admin/retrieval-traces/${traceId}/replay`))
};

export const adminSecurityApi = {
  users: () => data<SecurityUser[]>(client.get('/admin/security/users')),
  grantDataset: (userId: string, datasetId: number, permission = 'READ') =>
    data<{ userId: string; datasetId: number; permission: string }>(client.post(`/admin/security/users/${encodeURIComponent(userId)}/datasets/${datasetId}`, { permission })),
  revokeDataset: (userId: string, datasetId: number) =>
    data<void>(client.delete(`/admin/security/users/${encodeURIComponent(userId)}/datasets/${datasetId}`))
};

export const conversationApi = {
  create: (datasetId: number | undefined, title: string) => data<{ id: number; title: string }>(client.post('/conversations', { datasetId, title })),
  list: () => data<Conversation[]>(client.get('/conversations')),
  messages: (id: number) => data<ConversationEntry[]>(client.get(`/conversations/${id}/messages`)),
  archive: (id: number) => data<void>(client.post(`/conversations/${id}/archive`))
};

export const memoryApi = {
  list: (datasetId?: number, query = '') => data<MemoryRecord[]>(client.get('/memories', { params: { datasetId, query } })),
  remember: (body: { datasetId?: number; content: string; type: MemoryRecord['type']; memoryKey?: string }) =>
    data<MemoryRecord>(client.post('/memories/remember', body)),
  update: (item: MemoryRecord, body: { datasetId?: number; content: string; type: MemoryRecord['type']; memoryKey?: string }) =>
    data<MemoryRecord>(client.put(`/memories/${encodeURIComponent(item.id)}`, { ...body, confirmed: item.status === 'ACTIVE' })),
  confirm: (id: string) => data<MemoryRecord>(client.post(`/memories/${encodeURIComponent(id)}/confirm`)),
  reject: (id: string) => data<MemoryRecord>(client.post(`/memories/${encodeURIComponent(id)}/reject`)),
  pause: (id: string) => data<MemoryRecord>(client.post(`/memories/${encodeURIComponent(id)}/pause`)),
  resume: (id: string) => data<MemoryRecord>(client.post(`/memories/${encodeURIComponent(id)}/resume`)),
  remove: (id: string) => data<void>(client.delete(`/memories/${encodeURIComponent(id)}`)),
  clear: (datasetId?: number) => data<void>(client.delete('/memories', { params: { datasetId } })),
  export: (datasetId?: number) => data<MemoryRecord[]>(client.get('/memories/export', { params: { datasetId } })),
  settings: () => data<MemorySettings>(client.get('/memory-settings')),
  saveSettings: (body: MemorySettings) => data<MemorySettings>(client.put('/memory-settings', body))
};

export const monitorApi = {
  overview: (datasetId: number) => data<TokenUsage>(client.get('/monitor/overview', { params: { datasetId } })),
  modelHealth: () => data<ModelHealth[]>(client.get('/monitor/model-health')),
  saveModelCandidate: (body: Partial<ModelHealth>) => data<ModelHealth>(client.put('/monitor/model-candidates', body))
};

export const modelConfigApi = {
  list: () => data<ModelConfig[]>(client.get('/model-configs')),
  save: (body: { modelType: string; provider: string; modelName: string; baseUrl?: string; secretId?: string; apiKey?: string; enabled: boolean }) =>
    data<ModelConfig>(client.post('/model-configs', body)),
  validate: (id: string) => data<{ id: string; status: string; message: string; capabilities: string[] }>(client.post(`/model-configs/${encodeURIComponent(id)}/validate`)),
  revoke: (id: string) => data<void>(client.delete(`/model-configs/${encodeURIComponent(id)}`))
};

export const evalApi = {
  run: (datasetId: number, items: EvalItem[]) => data<EvalReport>(client.post('/evaluations/run', items, { params: { datasetId } })),
  compare: (datasetId: number, items: EvalItem[], topKs: number[]) => data<EvalComparison[]>(client.post('/evaluations/compare', { items, topKs }, { params: { datasetId } })),
  save: (datasetId: number, item: EvalItem) => data<number>(client.post('/evaluations/datasets', item, { params: { datasetId } })),
  update: (id: number, item: EvalItem) => data<void>(client.put(`/evaluations/datasets/${id}`, item)),
  remove: (id: number) => data<void>(client.delete(`/evaluations/datasets/${id}`)),
  fromTrace: (traceId: string) => data<number>(client.post(`/evaluations/from-trace/${traceId}`)),
  bootstrap: (datasetId: number, limit = 20) => data<{ created: number; items: EvalItem[] }>(client.post('/evaluations/bootstrap', null, { params: { datasetId, limit } })),
  securityRedTeam: (datasetId: number) => data<{ created: number; items: EvalItem[] }>(client.post('/evaluations/security-redteam', null, { params: { datasetId } })),
  list: (datasetId: number) => data<EvalItem[]>(client.get('/evaluations/datasets', { params: { datasetId } })),
  runSaved: (datasetId: number) => data<{ taskId: number; report: EvalReport }>(client.post('/evaluations/tasks', null, { params: { datasetId } })),
  tasks: (datasetId: number) => data<EvalTask[]>(client.get('/evaluations/tasks', { params: { datasetId } })),
  report: (taskId: number) => data<EvalReport>(client.get(`/evaluations/${taskId}`))
};
