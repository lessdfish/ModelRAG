import {describe,expect,it} from 'vitest';
import type {Message} from '../types';
import {completedStatus,conversationTurnCount,formatLatency,patchMessageList,THINKING_TEXT} from './chatState';

describe('chat message state',()=>{
  it('keeps earlier citations when a later answer changes',()=>{
    const messages:Message[]=[
      {id:'first',role:'assistant',content:'五天',citations:[{chunkId:3,excerpt:'年假五天',score:.9}],status:'DONE'},
      {id:'second',role:'assistant',content:THINKING_TEXT,status:'THINKING'}
    ];

    const updated=patchMessageList(messages,'second',{content:'证据不足',status:'DONE'});

    expect(updated[0].citations).toEqual(messages[0].citations);
    expect(updated[1]).toMatchObject({content:'证据不足',status:'DONE'});
  });

  it('shows DONE for answered and cleanly refused completed requests',()=>{
    expect(completedStatus('DONE')).toBe('DONE');
    expect(completedStatus('REFUSED')).toBe('DONE');
    expect(completedStatus('WAITING_APPROVAL')).toBeUndefined();
  });

  it('shows conversation turns rather than counting both sides of each exchange',()=>{
    expect(conversationTurnCount(6)).toBe(3);
    expect(conversationTurnCount(1)).toBe(1);
    expect(formatLatency(2345)).toBe('2.3 秒');
  });
});
