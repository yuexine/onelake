package com.onelake.analytics.llm;

import java.util.List;
import java.util.Map;

/**
 * LLM 客户端 SPI（提供商抽象）。
 *
 * 设计：
 * - 提供 chatCompletion 接口，按当前租户/应用配置路由到 OpenAI/Claude/Ollama
 * - 默认实现：OpenAiLlmClient（key 未配置时抛 IllegalStateException，前端给友好提示）
 * - 后续可在 module-analytics 增加多个 @Component("ollamaLlmClient") / @Component("anthropicLlmClient")，
 *   由配置切换 onelake.analytics.llm.provider=openai|anthropic|ollama
 */
public interface LlmClient {

    /**
     * Chat completion（NL2SQL 的核心调用）。
     *
     * @param systemPrompt 系统提示（设定角色、规则）
     * @param userPrompt   用户输入（含 schema + 自然语言问题）
     * @return 生成的回答（pure text）
     */
    String chatCompletion(String systemPrompt, String userPrompt);

    /**
     * 提供商标识（openai/anthropic/ollama），用于 metrics + 降级路由。
     */
    String provider();

    /**
     * 模型可用性探测（启动时调用，便于 UI 显示当前 provider 状态）。
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * 单条消息的简单封装（便于扩展 function call）。
     */
    record ChatMessage(String role, String content) {
        public static ChatMessage system(String text) { return new ChatMessage("system", text); }
        public static ChatMessage user(String text) { return new ChatMessage("user", text); }
        public Map<String, Object> toMap() {
            return Map.of("role", role, "content", content);
        }
        public static List<Map<String, Object>> toList(ChatMessage... msgs) {
            return java.util.Arrays.stream(msgs).map(ChatMessage::toMap).toList();
        }
    }
}
