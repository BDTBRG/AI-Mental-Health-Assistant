package com.amha.service;

import com.amha.dto.AgentMemoryRequest;
import com.amha.dto.AgentMemoryVO;

import java.util.List;

public interface AgentMemoryService {
    boolean isPersonalizationEnabled(Long userId);
    void setPersonalizationEnabled(Long userId, Boolean enabled);
    List<AgentMemoryVO> list(Long userId);
    AgentMemoryVO create(Long userId, AgentMemoryRequest request);
    void update(Long userId, Long memoryId, AgentMemoryRequest request);
    void delete(Long userId, Long memoryId);
    void clearAndDisable(Long userId);
}
