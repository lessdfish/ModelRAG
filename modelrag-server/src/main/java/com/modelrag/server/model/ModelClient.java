package com.modelrag.server.model; public interface ModelClient { String name(); ModelType type(); String execute(String input); }
