import {describe, expect, it} from 'vitest';
import {parseSseFrame} from './useSSE';

describe('parseSseFrame', () => {
  it('parses JSON data across a multiline SSE frame', () => {
    expect(parseSseFrame('event: TOKEN\ndata: {"type":"TOKEN",\ndata: "message":"message","data":{"text":"片段"}}'))
      .toEqual({type: 'TOKEN', message: 'message', data: {text: '片段'}});
  });

  it('ignores empty and malformed frames', () => {
    expect(parseSseFrame('event: HEARTBEAT\n')).toBeUndefined();
    expect(parseSseFrame('data: not-json')).toBeUndefined();
  });
});
