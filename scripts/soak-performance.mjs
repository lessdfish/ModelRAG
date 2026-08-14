#!/usr/bin/env node

import { mkdir, writeFile } from "node:fs/promises";
import { resolve } from "node:path";

const options = parseArgs(process.argv.slice(2));
if (options.help) {
  printHelp();
  process.exit(0);
}

const baseUrl = trimSlash(options.baseUrl ?? process.env.MODELRAG_ACCEPTANCE_URL ?? "http://127.0.0.1:8091");
const datasetId = positiveInteger(options.datasetId ?? process.env.MODELRAG_ACCEPTANCE_DATASET_ID, "dataset-id");
const durationSeconds = positiveInteger(options.durationSeconds ?? "300", "duration-seconds");
const concurrency = positiveInteger(options.concurrency ?? "4", "concurrency");
const launchIntervalMs = positiveInteger(options.launchIntervalMs ?? "3000", "launch-interval-ms");
const requestTimeoutMs = positiveInteger(options.requestTimeoutMs ?? "120000", "request-timeout-ms");
const agentEvery = nonNegativeInteger(options.agentEvery ?? "20", "agent-every");
const strictSoak = Boolean(options.strictSoak);
const question = options.question ?? process.env.MODELRAG_ACCEPTANCE_QUESTION
  ?? "请根据知识库说明核心制度，并给出引用。";
const agentQuestion = options.agentQuestion ?? process.env.MODELRAG_ACCEPTANCE_AGENT_QUESTION
  ?? "分别查找制度适用范围；然后查找责任角色；然后查找审批条件；然后查找时间限制；然后查找例外规则；然后汇总以上六项并给出引用";
const outputFile = resolve(options.output ?? `output/soak-${new Date().toISOString().replaceAll(":", "-")}.json`);

if (strictSoak && durationSeconds < 86_400) {
  throw new Error("--strict-soak requires --duration-seconds >= 86400");
}

const bearer = process.env.MODELRAG_ACCEPTANCE_TOKEN || await login();
const startedAt = new Date();
const deadline = Date.now() + durationSeconds * 1000;
const samples = [];
const healthChecks = [];
let sequence = 0;
let stop = false;

const initialMetrics = await operationalSnapshot();
const conversations = await Promise.all(Array.from({ length: concurrency }, (_, index) => createConversation(index)));
const workers = conversations.map((conversationId, index) => worker(index, conversationId));
const healthMonitor = monitorHealth();
await Promise.all(workers);
stop = true;
await healthMonitor;
const conversationChecks = await Promise.all(conversations.map(checkConversation));
const finalMetrics = await operationalSnapshot();

const successful = samples.filter((sample) => sample.status === "DONE");
const failed = samples.filter((sample) => sample.status !== "DONE");
const direct = successful.filter((sample) => sample.mode === "DIRECT_RAG");
const agents = successful.filter((sample) => sample.mode === "AGENT");
const sixStepAgents = agents.filter((sample) => sample.actEvents >= 6 || sample.maxStep >= 6);
const firstToken = direct.map((sample) => sample.firstTokenMs).filter(Number.isFinite);
const complete = direct.map((sample) => sample.completeMs).filter(Number.isFinite);
const degraded = successful.filter((sample) => sample.degradedComponents.length > 0);
const report = {
  gate: {
    strictSoak,
    requiredDurationSeconds: strictSoak ? 86_400 : null,
    firstTokenP95LimitMs: 3_000,
    completeP95LimitMs: 10_000,
  },
  run: {
    startedAt: startedAt.toISOString(),
    finishedAt: new Date().toISOString(),
    requestedDurationSeconds: durationSeconds,
    elapsedSeconds: Math.round((Date.now() - startedAt.getTime()) / 1000),
    baseUrl,
    datasetId,
    concurrency,
    aggregateLaunchIntervalMs: launchIntervalMs,
    agentEvery,
    sampleCount: samples.length,
  },
  result: {
    passed: false,
    successful: successful.length,
    failed: failed.length,
    degraded: degraded.length,
    directRagSuccessful: direct.length,
    agentSuccessful: agents.length,
    sixStepAgentSuccessful: sixStepAgents.length,
    minimumConversationMessages: conversationChecks.length
      ? Math.min(...conversationChecks.map((item) => item.messageCount)) : 0,
    firstTokenP95Ms: percentile(firstToken, 0.95),
    completeP95Ms: percentile(complete, 0.95),
    firstTokenMaxMs: firstToken.length ? Math.max(...firstToken) : null,
    completeMaxMs: complete.length ? Math.max(...complete) : null,
    readinessFailures: healthChecks.filter((check) => !check.up).length,
  },
  operationalSnapshots: { initial: initialMetrics, final: finalMetrics },
  conversationChecks,
  healthChecks,
  failures: failed.slice(0, 100),
  degradedSamples: degraded.slice(0, 100),
};
report.result.passed = report.result.successful > 0
  && report.result.directRagSuccessful > 0
  && report.result.failed === 0
  && report.result.degraded === 0
  && report.result.readinessFailures === 0
  && report.result.firstTokenP95Ms <= report.gate.firstTokenP95LimitMs
  && report.result.completeP95Ms <= report.gate.completeP95LimitMs
  && (!strictSoak || report.result.agentSuccessful > 0)
  && (!strictSoak || report.result.sixStepAgentSuccessful > 0)
  && (!strictSoak || report.result.minimumConversationMessages >= 24)
  && (!strictSoak || report.run.elapsedSeconds >= report.gate.requiredDurationSeconds);

await mkdir(resolve(outputFile, ".."), { recursive: true });
await writeFile(outputFile, `${JSON.stringify(report, null, 2)}\n`, "utf8");
console.log(JSON.stringify({ outputFile, ...report.result }));
process.exitCode = report.result.passed ? 0 : 1;

async function worker(workerIndex, conversationId) {
  const workerIntervalMs = launchIntervalMs * concurrency;
  while (Date.now() < deadline) {
    const sampleNumber = ++sequence;
    const cycleStarted = Date.now();
    const agent = agentEvery > 0 && sampleNumber % agentEvery === 0;
    samples.push(await ask(workerIndex, sampleNumber, conversationId, agent));
    const remaining = workerIntervalMs - (Date.now() - cycleStarted);
    if (remaining > 0 && Date.now() < deadline) await delay(Math.min(remaining, deadline - Date.now()));
  }
}

async function ask(workerIndex, sampleNumber, conversationId, agent) {
  const started = performance.now();
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(new Error("request timeout")), requestTimeoutMs);
  const sample = {
    worker: workerIndex,
    sequence: sampleNumber,
    mode: agent ? "AGENT" : "DIRECT_RAG",
    startedAt: new Date().toISOString(),
    status: "ERROR",
    firstTokenMs: null,
    completeMs: null,
    degradedComponents: [],
    actEvents: 0,
    maxStep: 0,
    error: null,
  };
  try {
    const response = await fetch(`${baseUrl}/api/v2/assistant/streams`, {
      method: "POST",
      headers: authHeaders({ "Content-Type": "application/json", "Idempotency-Key": crypto.randomUUID() }),
      body: JSON.stringify({ datasetId, question: agent ? agentQuestion : question, conversationId, agent }),
      signal: controller.signal,
    });
    if (!response.ok || !response.body) throw new Error(`stream HTTP ${response.status}: ${await safeText(response)}`);
    await consumeSse(response.body, (event, data) => {
      if (event === "TOKEN" && sample.firstTokenMs === null) {
        sample.firstTokenMs = Math.round(performance.now() - started);
      }
      if (event === "ANSWER" && Array.isArray(data?.data?.degradedComponents)) {
        sample.degradedComponents = data.data.degradedComponents.map(String);
      }
      if (event === "ACT") sample.actEvents++;
      if (Number.isFinite(data?.data?.step)) sample.maxStep = Math.max(sample.maxStep, Number(data.data.step));
      if (event === "ERROR") throw new Error("server emitted ERROR event");
      if (event === "DONE") sample.status = "DONE";
    });
    sample.completeMs = Math.round(performance.now() - started);
    if (sample.status !== "DONE") throw new Error("SSE stream ended without DONE");
    if (!agent && sample.firstTokenMs === null) throw new Error("SSE stream completed without TOKEN");
  } catch (error) {
    sample.error = error instanceof Error ? error.message : String(error);
    sample.completeMs = Math.round(performance.now() - started);
  } finally {
    clearTimeout(timeout);
  }
  return sample;
}

async function checkConversation(conversationId) {
  try {
    const response = await fetch(`${baseUrl}/api/v2/conversations/${conversationId}/messages`, {
      headers: authHeaders(),
    });
    if (!response.ok) return { conversationId, messageCount: 0, error: `HTTP ${response.status}` };
    const payload = await response.json();
    return { conversationId, messageCount: Array.isArray(payload?.data) ? payload.data.length : 0, error: null };
  } catch (error) {
    return { conversationId, messageCount: 0, error: error instanceof Error ? error.message : String(error) };
  }
}

async function consumeSse(stream, onEvent) {
  const reader = stream.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  while (true) {
    const { done, value } = await reader.read();
    buffer += decoder.decode(value, { stream: !done });
    const blocks = buffer.split(/\r?\n\r?\n/);
    buffer = blocks.pop() ?? "";
    for (const block of blocks) {
      let event = "message";
      const dataLines = [];
      for (const line of block.split(/\r?\n/)) {
        if (line.startsWith("event:")) event = line.slice(6).trim();
        if (line.startsWith("data:")) dataLines.push(line.slice(5).trimStart());
      }
      let data = null;
      if (dataLines.length) {
        const raw = dataLines.join("\n");
        try { data = JSON.parse(raw); } catch { data = raw; }
      }
      onEvent(event, data);
    }
    if (done) break;
  }
}

async function login() {
  const username = process.env.MODELRAG_ACCEPTANCE_USERNAME;
  const password = process.env.MODELRAG_ACCEPTANCE_PASSWORD;
  if (!username || !password) {
    throw new Error("Set MODELRAG_ACCEPTANCE_TOKEN or MODELRAG_ACCEPTANCE_USERNAME and MODELRAG_ACCEPTANCE_PASSWORD");
  }
  const response = await fetch(`${baseUrl}/api/v2/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json", "Idempotency-Key": crypto.randomUUID() },
    body: JSON.stringify({ username, password }),
  });
  if (!response.ok) throw new Error(`login HTTP ${response.status}`);
  const payload = await response.json();
  if (!payload?.data?.token) throw new Error("login response did not contain data.token");
  return payload.data.token;
}

async function createConversation(index) {
  const response = await fetch(`${baseUrl}/api/v2/conversations`, {
    method: "POST",
    headers: authHeaders({ "Content-Type": "application/json", "Idempotency-Key": crypto.randomUUID() }),
    body: JSON.stringify({ datasetId, title: `soak-worker-${index}-${Date.now()}` }),
  });
  if (!response.ok) throw new Error(`create conversation HTTP ${response.status}: ${await safeText(response)}`);
  const payload = await response.json();
  const id = payload?.data?.id;
  if (!Number.isSafeInteger(id)) throw new Error("create conversation response did not contain data.id");
  return id;
}

async function monitorHealth() {
  while (!stop && Date.now() < deadline) {
    const checkedAt = new Date().toISOString();
    try {
      const response = await fetch(`${baseUrl}/actuator/health/readiness`);
      const payload = response.ok ? await response.json() : null;
      healthChecks.push({ checkedAt, up: response.ok && payload?.status === "UP", status: payload?.status ?? response.status });
    } catch (error) {
      healthChecks.push({ checkedAt, up: false, status: error instanceof Error ? error.message : String(error) });
    }
    await delay(Math.min(60_000, Math.max(0, deadline - Date.now())));
  }
}

async function operationalSnapshot() {
  const snapshot = { checkedAt: new Date().toISOString(), readiness: null, prometheus: {} };
  try {
    const readiness = await fetch(`${baseUrl}/actuator/health/readiness`);
    snapshot.readiness = readiness.ok ? await readiness.json() : { httpStatus: readiness.status };
  } catch (error) {
    snapshot.readiness = { error: error instanceof Error ? error.message : String(error) };
  }
  try {
    const metrics = await fetch(`${baseUrl}/actuator/prometheus`, { headers: authHeaders() });
    if (metrics.ok) snapshot.prometheus = selectMetrics(await metrics.text());
    else snapshot.prometheus = { httpStatus: metrics.status };
  } catch (error) {
    snapshot.prometheus = { error: error instanceof Error ? error.message : String(error) };
  }
  return snapshot;
}

function selectMetrics(text) {
  const prefixes = [
    "jvm_memory_used_bytes", "jvm_gc_pause_seconds_count", "jvm_gc_pause_seconds_sum",
    "hikaricp_connections_active", "hikaricp_connections_pending", "executor_active_threads",
    "executor_queued_tasks", "process_cpu_usage", "system_cpu_usage",
  ];
  return Object.fromEntries(text.split(/\r?\n/)
    .filter((line) => prefixes.some((prefix) => line.startsWith(prefix)))
    .map((line, index) => [`metric-${index + 1}`, line]));
}

function percentile(values, quantile) {
  if (!values.length) return null;
  const sorted = [...values].sort((left, right) => left - right);
  return sorted[Math.max(0, Math.ceil(sorted.length * quantile) - 1)];
}

function authHeaders(extra = {}) {
  return { Authorization: `Bearer ${bearer}`, ...extra };
}

async function safeText(response) {
  try { return (await response.text()).slice(0, 500); } catch { return ""; }
}

function delay(milliseconds) {
  return new Promise((resolveDelay) => setTimeout(resolveDelay, Math.max(0, milliseconds)));
}

function trimSlash(value) {
  return String(value).replace(/\/+$/, "");
}

function positiveInteger(value, name) {
  const parsed = Number.parseInt(String(value ?? ""), 10);
  if (!Number.isSafeInteger(parsed) || parsed <= 0) throw new Error(`--${name} must be a positive integer`);
  return parsed;
}

function nonNegativeInteger(value, name) {
  const parsed = Number.parseInt(String(value ?? ""), 10);
  if (!Number.isSafeInteger(parsed) || parsed < 0) throw new Error(`--${name} must be a non-negative integer`);
  return parsed;
}

function parseArgs(args) {
  const parsed = {};
  for (let index = 0; index < args.length; index++) {
    const argument = args[index];
    if (argument === "--help" || argument === "-h") parsed.help = true;
    else if (argument === "--strict-soak") parsed.strictSoak = true;
    else if (argument.startsWith("--")) {
      const key = argument.slice(2).replace(/-([a-z])/g, (_, letter) => letter.toUpperCase());
      if (index + 1 >= args.length || args[index + 1].startsWith("--")) throw new Error(`${argument} requires a value`);
      parsed[key] = args[++index];
    } else throw new Error(`Unknown argument: ${argument}`);
  }
  return parsed;
}

function printHelp() {
  console.log(`ModelRAG real-provider SSE performance and soak gate

Required environment:
  MODELRAG_ACCEPTANCE_DATASET_ID
  MODELRAG_ACCEPTANCE_TOKEN
    or MODELRAG_ACCEPTANCE_USERNAME and MODELRAG_ACCEPTANCE_PASSWORD

Usage:
  node scripts/soak-performance.mjs [options]

Options:
  --base-url URL                 Default http://127.0.0.1:8091
  --dataset-id ID                Overrides MODELRAG_ACCEPTANCE_DATASET_ID
  --duration-seconds N           Default 300
  --concurrency N                Default 4
  --launch-interval-ms N         Aggregate launch interval; default 3000
  --request-timeout-ms N         Default 120000
  --agent-every N                Every Nth request is a six-subtask Agent run; 0 disables, default 20
  --question TEXT                RAG question used for every sample
  --agent-question TEXT          Agent workload; default has six safe knowledge subtasks
  --output FILE                  JSON report path
  --strict-soak                  Require at least 86400 seconds and all gates

Exit 0 requires: no request/readiness/degradation failures, direct-RAG first TOKEN p95 <= 3s,
and direct-RAG complete SSE p95 <= 10s. With --strict-soak it also requires >= 24 hours,
at least 24 persisted messages per worker conversation, and an Agent sample that executes six steps.`);
}
