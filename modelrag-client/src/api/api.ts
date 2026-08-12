import client from './client';
import type {
  AbExperiment,
  AbReport,
  AgentResult,
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
  ModelHealth,
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
  me: () => data<AuthUser>(client.get('/auth/me'))
};

export const kbApi = {
  list: () => data<Dataset[]>(client.get('/knowledge-bases')),
  create: (body: KnowledgeBasePayload) => data<Dataset>(client.post('/knowledge-bases', body)),
  update: (id: number, body: KnowledgeBasePayload) => data<Dataset>(client.put(`/knowledge-bases/${id}`, body)),
  remove: (id: number) => data<void>(client.delete(`/knowledge-bases/${id}`)),
  rebuild: (id: number) => data<{ documents: number; requeued: number }>(client.post(`/knowledge-bases/${id}/rebuild-index`)),
  documents: (id: number) => data<Document[]>(client.get(`/knowledge-bases/${id}/documents`)),
  removeDocument: (datasetId: number, documentId: number) => data<void>(client.delete(`/knowledge-bases/${datasetId}/documents/${documentId}`)),
  chunks: (datasetId: number, documentId: number) => data<Chunk[]>(client.get(`/knowledge-bases/${datasetId}/documents/${documentId}/chunks`)),
  upload: (id: number, file: File) => {
    const form = new FormData();
    form.append('file', file);
    return data<Document>(client.post(`/knowledge-bases/${id}/documents`, form));
  }
};

export const qaApi = {
  ask: (datasetId: number, query: string, conversationId?: number) => data<QaResult>(client.post('/qa/ask', { datasetId, query, conversationId })),
  auto: (query: string, conversationId?: number) => data<AutoQaResult>(client.post('/qa/auto', { query, conversationId })),
  feedback: (traceId: string, rating: 'LIKE' | 'DISLIKE', comment = '') => data<Feedback>(client.post('/qa/feedback', { traceId, rating, comment })),
  contextPolicy: () => data<ContextPolicy>(client.get('/qa/context-policy'))
};

export const agentApi = {
  run: (datasetId: number, query: string, conversationId?: number) => data<AgentResult>(client.post('/qa/agent', { datasetId, query, conversationId })),
  approvals: () => data<Approval[]>(client.get('/qa/agent/approvals')),
  approve: (approvalId: string, datasetId: number, query: string, approved: boolean, conversationId?: number) =>
    data<AgentResult>(client.post(`/qa/agent/approval/${approvalId}?approved=${approved}`, { datasetId, query, conversationId }))
};

export const toolApi = {
  list: () => data<ToolDefinition[]>(client.get('/tools')),
  all: () => data<ToolDefinition[]>(client.get('/tools/all')),
  register: (tool: ToolDefinition) => data<ToolDefinition>(client.post('/tools', tool)),
  setEnabled: (name: string, value: boolean) => data<ToolDefinition>(client.post(`/tools/${encodeURIComponent(name)}/enabled`, null, { params: { value } })),
  remove: (name: string) => data<void>(client.delete(`/tools/${encodeURIComponent(name)}`))
};

export const intentApi = {
  list: (datasetId: number) => data<IntentNode[]>(client.get(`/knowledge-bases/${datasetId}/intents`)),
  create: (datasetId: number, node: IntentNode) => data<IntentNode>(client.post(`/knowledge-bases/${datasetId}/intents`, node)),
  update: (datasetId: number, id: number, node: IntentNode) => data<IntentNode>(client.put(`/knowledge-bases/${datasetId}/intents/${id}`, node)),
  remove: (datasetId: number, id: number) => data<void>(client.delete(`/knowledge-bases/${datasetId}/intents/${id}`))
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

export const monitorApi = {
  overview: (datasetId: number) => data<TokenUsage>(client.get('/monitor/overview', { params: { datasetId } })),
  modelHealth: () => data<ModelHealth[]>(client.get('/monitor/model-health')),
  saveModelCandidate: (body: Partial<ModelHealth>) => data<ModelHealth>(client.put('/monitor/model-candidates', body))
};

export const experimentApi = {
  list: (datasetId: number) => data<AbExperiment[]>(client.get('/experiments', { params: { datasetId } })),
  save: (item: Partial<AbExperiment>) => data<AbExperiment>(client.post('/experiments', item)),
  setEnabled: (id: string, value: boolean) => data<AbExperiment>(client.post(`/experiments/${encodeURIComponent(id)}/enabled`, null, { params: { value } })),
  remove: (id: string) => data<void>(client.delete(`/experiments/${encodeURIComponent(id)}`)),
  report: (datasetId: number) => data<AbReport[]>(client.get('/experiments/report', { params: { datasetId } }))
};

export const evalApi = {
  run: (datasetId: number, items: EvalItem[]) => data<EvalReport>(client.post('/eval/run', items, { params: { datasetId } })),
  compare: (datasetId: number, items: EvalItem[], topKs: number[]) => data<EvalComparison[]>(client.post('/eval/compare', { items, topKs }, { params: { datasetId } })),
  save: (datasetId: number, item: EvalItem) => data<number>(client.post('/eval/datasets', item, { params: { datasetId } })),
  update: (id: number, item: EvalItem) => data<void>(client.put(`/eval/datasets/${id}`, item)),
  remove: (id: number) => data<void>(client.delete(`/eval/datasets/${id}`)),
  fromTrace: (traceId: string) => data<number>(client.post(`/eval/from-trace/${traceId}`)),
  bootstrap: (datasetId: number, limit = 20) => data<{ created: number; items: EvalItem[] }>(client.post('/eval/bootstrap', null, { params: { datasetId, limit } })),
  securityRedTeam: (datasetId: number) => data<{ created: number; items: EvalItem[] }>(client.post('/eval/security-redteam', null, { params: { datasetId } })),
  list: (datasetId: number) => data<EvalItem[]>(client.get('/eval/datasets', { params: { datasetId } })),
  runSaved: (datasetId: number) => data<{ taskId: number; report: EvalReport }>(client.post('/eval/tasks', null, { params: { datasetId } })),
  tasks: (datasetId: number) => data<EvalTask[]>(client.get('/eval/tasks', { params: { datasetId } })),
  report: (taskId: number) => data<EvalReport>(client.get(`/eval/tasks/${taskId}/report`))
};
