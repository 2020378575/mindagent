package com.mindbridge.agent.service.task;

import com.mindbridge.agent.domain.ResearchTaskType;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
/**
 * 启动时构建 type -> handler 映射；同一类型注册两次直接失败。
 */
public class ResearchTaskHandlerRegistry {

    private final Map<ResearchTaskType, ResearchTaskHandler> handlers;

    public ResearchTaskHandlerRegistry(List<ResearchTaskHandler> handlerList) {
        EnumMap<ResearchTaskType, ResearchTaskHandler> map = new EnumMap<>(ResearchTaskType.class);
        for (ResearchTaskHandler handler : handlerList) {
            ResearchTaskHandler previous = map.put(handler.type(), handler);
            if (previous != null) {
                throw new IllegalStateException("Duplicate research task handler for type " + handler.type());
            }
        }
        this.handlers = Map.copyOf(map);
    }

    public ResearchTaskHandler require(ResearchTaskType type) {
        ResearchTaskHandler handler = handlers.get(type);
        if (handler == null) {
            throw new IllegalStateException("No research task handler registered for type " + type);
        }
        return handler;
    }
}
