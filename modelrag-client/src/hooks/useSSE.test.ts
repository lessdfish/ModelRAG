import {describe,expect,it} from 'vitest';
import {appendSseAccessToken} from './useSSE';

describe('appendSseAccessToken',()=>{
  it('adds access_token for EventSource authentication',()=>{
    expect(appendSseAccessToken('/api/v1/qa/auto/stream?q=test','abc.def','http://localhost:5173'))
      .toBe('/api/v1/qa/auto/stream?q=test&access_token=abc.def');
  });

  it('keeps an existing access_token unchanged',()=>{
    expect(appendSseAccessToken('/api/v1/qa/auto/stream?q=test&access_token=existing','new-token','http://localhost:5173'))
      .toBe('/api/v1/qa/auto/stream?q=test&access_token=existing');
  });

  it('preserves hash and skips empty tokens',()=>{
    expect(appendSseAccessToken('/api/v1/events#tail',null,'http://localhost:5173')).toBe('/api/v1/events#tail');
    expect(appendSseAccessToken('/api/v1/events#tail','token','http://localhost:5173')).toBe('/api/v1/events?access_token=token#tail');
  });
});
