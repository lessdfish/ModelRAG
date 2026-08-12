package com.modelrag.knowledge.splitter;
import java.util.ArrayList;
import java.util.List;
/** 512 characters with 64 overlap is the default portability-safe approximation of the specified token policy. */
public final class RecursiveCharSplitter implements ChunkSplitter {
 public List<String> split(String text, int size, int overlap) { if(size<1 || overlap<0 || overlap>=size) throw new IllegalArgumentException("invalid chunk parameters"); String normalized=text==null?"":text.trim(); List<String> out=new ArrayList<>(); for(int start=0;start<normalized.length();) {int end=Math.min(normalized.length(),start+size); if(end<normalized.length()){int boundary=Math.max(normalized.lastIndexOf('\n',end),normalized.lastIndexOf(' ',end));if(boundary>start+size/2)end=boundary;}String part=normalized.substring(start,end).trim();if(!part.isEmpty())out.add(part);if(end==normalized.length())break;start=Math.max(start+1,end-overlap);}return out; }
}
