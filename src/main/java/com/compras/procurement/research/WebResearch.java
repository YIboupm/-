package com.compras.procurement.research;

import java.util.List;

/**
 * Live web research for the intake agent: find current products, specs and list prices. One provider today
 * (DeepSeek Search/Fetch); another (Tavily, a company search proxy...) is a new implementation of this interface.
 */
public interface WebResearch {

    boolean available();

    List<WebResult> search(String query, int count);

    WebPage fetch(String url);
}
