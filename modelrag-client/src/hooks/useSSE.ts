import { useEffect, useState } from 'react';

export type StreamEvent = { type: string; message: string; data: Record<string, unknown> };
export type SSERequest = { url: string; method?: 'GET' | 'POST'; body?: unknown };

export function parseSseFrame(frame: string): StreamEvent | undefined {
  const value = frame.split(/\r?\n/)
    .filter(line => line.startsWith('data:'))
    .map(line => line.slice(5).trim())
    .join('');
  if (!value) return undefined;
  try { return JSON.parse(value) as StreamEvent; } catch { return undefined; }
}

export function useSSE(request?: string | SSERequest) {
  const [connected, setConnected] = useState(false);
  const [events, setEvents] = useState<StreamEvent[]>([]);

  useEffect(() => {
    if (!request) {
      setConnected(false);
      return;
    }
    setEvents([]);
    let closed = false;
    const controller = new AbortController();
    const target = typeof request === 'string' ? { url: request, method: 'GET' as const } : request;

    const open = async () => {
      try {
        const token = localStorage.getItem('modelrag.auth.token');
        const headers: Record<string, string> = {};
        if (token) headers.Authorization = `Bearer ${token}`;
        if (target.body !== undefined) headers['Content-Type'] = 'application/json';
        const response = await fetch(target.url, {
          method: target.method || 'GET',
          headers,
          body: target.body === undefined ? undefined : JSON.stringify(target.body),
          signal: controller.signal
        });
        if (!response.ok || !response.body) throw new Error('SSE connection failed');
        setConnected(true);
        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';
        while (!closed) {
          const next = await reader.read();
          if (next.done) break;
          buffer += decoder.decode(next.value, { stream: true });
          const frames = buffer.split(/\r?\n\r?\n/);
          buffer = frames.pop() || '';
          for (const frame of frames) {
            const event = parseSseFrame(frame);
            if (event) setEvents(current => [...current.slice(-19), event]);
          }
        }
      } catch {
        if (!closed) setConnected(false);
      } finally {
        if (!closed) setConnected(false);
      }
    };
    void open();
    return () => { closed = true; controller.abort(); };
  }, [targetKey(request)]);

  return { connected, events };
}

function targetKey(request?: string | SSERequest) {
  if (!request) return '';
  if (typeof request === 'string') return request;
  return `${request.method || 'GET'}:${request.url}:${JSON.stringify(request.body ?? null)}`;
}
