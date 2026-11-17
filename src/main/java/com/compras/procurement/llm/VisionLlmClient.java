package com.compras.procurement.llm;

import java.util.List;

/** A client that can send one image with the conversation (e.g. a photo of a delivery note). */
public interface VisionLlmClient {

    /**
     * @param imageDataUrl a data URL ("data:image/jpeg;base64,...") attached to the last user message
     */
    LlmResponse completeJsonWithImage(List<ChatMessage> messages, String imageDataUrl);
}
