package com.modelrag.toolgateway.catalog;

import java.util.List;

/** Safe catalog boundary for Agents, admin views, and public adapters. */
public interface ToolCatalog {
    ToolDescriptor register(ToolRegistrationCommand command);

    ToolDescriptor remove(String name);

    ToolDescriptor setEnabled(String name, boolean enabled);

    ToolDescriptor get(String name);

    ToolDescriptor getAny(String name);

    List<ToolDescriptor> list();

    List<ToolDescriptor> listEnabled();
}
