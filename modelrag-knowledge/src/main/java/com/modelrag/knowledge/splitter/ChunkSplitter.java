package com.modelrag.knowledge.splitter;
import java.util.List;
public interface ChunkSplitter { List<String> split(String text, int size, int overlap); }
