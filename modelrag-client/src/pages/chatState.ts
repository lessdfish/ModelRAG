import type {Message} from '../types';

export const THINKING_TEXT='正在思考中';

export function patchMessageList(messages:Message[],id:string,update:Partial<Message>){
  return messages.map(item=>item.id===id?{...item,...update}:item);
}

export function completedStatus(status:string){
  return status==='DONE'||status==='REFUSED'?'DONE':undefined;
}

/** A conversation turn consists of one user message and its assistant reply. */
export function conversationTurnCount(messageCount:number){
  return Math.ceil(Math.max(0,messageCount)/2);
}

export function formatLatency(latencyMs:number){
  return latencyMs<1_000?`${latencyMs} ms`:`${(latencyMs/1_000).toFixed(1)} 秒`;
}
