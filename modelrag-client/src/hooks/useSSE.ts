import {useEffect,useState} from 'react';
import {AUTH_TOKEN_KEY} from '../api/client';

export type StreamEvent={type:string;message:string;data:Record<string,unknown>};

export function useSSE(url?:string){
  const [connected,setConnected]=useState(false),[events,setEvents]=useState<StreamEvent[]>([]);
  useEffect(()=>{
    if(!url){setConnected(false);return;}
    setEvents([]);let delay=500,closed=false,es:EventSource,retry:number|undefined;
    const open=()=>{
      if(closed)return;
      es=new EventSource(withToken(url));
      es.onopen=()=>{setConnected(true);delay=500};
      es.onerror=()=>{setConnected(false);es.close();retry=window.setTimeout(open,delay);delay=Math.min(delay*2,10000)};
      ['THINK','PLAN','ACT','OBSERVE','ANSWER','THINKING','ROUTING','RETRIEVING','GENERATING','APPROVAL_REQUIRED','TOOL_CALL','DONE','ERROR','PARSING','CHUNKING','INDEXING','READY','FAILED'].forEach(type=>es.addEventListener(type,event=>{
        const payload=JSON.parse((event as MessageEvent<string>).data) as StreamEvent;
        setEvents(current=>[...current.slice(-19),payload]);
      }));
    };
    open();
    return()=>{closed=true;es?.close();if(retry)window.clearTimeout(retry);};
  },[url]);
  return {connected,events};
}

function withToken(url:string){
  const token=localStorage.getItem(AUTH_TOKEN_KEY);
  return appendSseAccessToken(url,token,window.location.origin);
}

export function appendSseAccessToken(url:string,token:string|null,origin:string){
  if(!token)return url;
  const next=new URL(url,origin);
  if(!next.searchParams.has('access_token'))next.searchParams.set('access_token',token);
  return next.pathname+next.search+next.hash;
}
