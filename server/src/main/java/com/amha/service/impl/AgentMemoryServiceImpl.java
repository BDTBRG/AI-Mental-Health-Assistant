package com.amha.service.impl;

import com.amha.common.BusinessException;
import com.amha.dto.AgentMemoryRequest;
import com.amha.dto.AgentMemoryVO;
import com.amha.entity.AgentMemory;
import com.amha.entity.AgentMemorySettings;
import com.amha.mapper.AgentMemoryMapper;
import com.amha.mapper.AgentMemorySettingsMapper;
import com.amha.service.AgentMemoryService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AgentMemoryServiceImpl implements AgentMemoryService {
    private static final int MAX_MEMORIES_PER_USER = 20;
    private final AgentMemoryMapper memoryMapper;
    private final AgentMemorySettingsMapper settingsMapper;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    public boolean isPersonalizationEnabled(Long userId) {
        AgentMemorySettings settings = settingsMapper.selectById(userId);
        return settings != null && Boolean.TRUE.equals(settings.getPersonalizationEnabled());
    }

    @Override
    @Transactional
    public void setPersonalizationEnabled(Long userId, Boolean enabled) {
        AgentMemorySettings settings = settingsMapper.selectById(userId);
        if (settings == null) {
            settings = new AgentMemorySettings();
            settings.setUserId(userId);
            settings.setPersonalizationEnabled(Boolean.TRUE.equals(enabled));
            settings.setUpdatedAt(LocalDateTime.now());
            settingsMapper.insert(settings);
        } else {
            settings.setPersonalizationEnabled(Boolean.TRUE.equals(enabled));
            settings.setUpdatedAt(LocalDateTime.now());
            settingsMapper.updateById(settings);
        }
        eventPublisher.publishEvent(new AgentMemoryChangedEvent(userId));
    }

    @Override
    public List<AgentMemoryVO> list(Long userId) {
        return memoryMapper.selectList(new LambdaQueryWrapper<AgentMemory>()
                        .eq(AgentMemory::getUserId, userId)
                        .orderByDesc(AgentMemory::getUpdatedAt)
                        .last("LIMIT " + MAX_MEMORIES_PER_USER))
                .stream().map(this::toVO).collect(Collectors.toList());
    }

    @Override
    @Transactional
    public AgentMemoryVO create(Long userId, AgentMemoryRequest request) {
        requireEnabled(userId);
        Long count = memoryMapper.selectCount(new LambdaQueryWrapper<AgentMemory>()
                .eq(AgentMemory::getUserId, userId));
        if (count != null && count >= MAX_MEMORIES_PER_USER) {
            throw new BusinessException("409", "最多保存20条个人记忆，请先整理已有内容");
        }
        AgentMemory memory = new AgentMemory();
        memory.setUserId(userId);
        memory.setContent(request.getContent().trim());
        memory.setSourceType("USER_ENTERED");
        memory.setExpiresAt(request.getExpiresAt());
        memoryMapper.insert(memory);
        eventPublisher.publishEvent(new AgentMemoryChangedEvent(userId));
        return toVO(memory);
    }

    @Override
    @Transactional
    public void update(Long userId, Long memoryId, AgentMemoryRequest request) {
        AgentMemory memory = requireOwnedMemory(userId, memoryId);
        memory.setContent(request.getContent().trim());
        memory.setExpiresAt(request.getExpiresAt());
        memoryMapper.updateById(memory);
        eventPublisher.publishEvent(new AgentMemoryChangedEvent(userId));
    }

    @Override
    @Transactional
    public void delete(Long userId, Long memoryId) {
        AgentMemory memory = requireOwnedMemory(userId, memoryId);
        memoryMapper.deleteById(memory.getId());
        eventPublisher.publishEvent(new AgentMemoryChangedEvent(userId));
    }

    @Override
    @Transactional
    public void clearAndDisable(Long userId) {
        memoryMapper.delete(new LambdaQueryWrapper<AgentMemory>().eq(AgentMemory::getUserId, userId));
        setPersonalizationEnabled(userId, false);
    }

    private void requireEnabled(Long userId) {
        if (!isPersonalizationEnabled(userId)) {
            throw new BusinessException("409", "请先开启个性化记忆授权");
        }
    }

    private AgentMemory requireOwnedMemory(Long userId, Long memoryId) {
        AgentMemory memory = memoryMapper.selectOne(new LambdaQueryWrapper<AgentMemory>()
                .eq(AgentMemory::getId, memoryId)
                .eq(AgentMemory::getUserId, userId));
        if (memory == null) throw new BusinessException("404", "记忆不存在");
        return memory;
    }

    private AgentMemoryVO toVO(AgentMemory memory) {
        return AgentMemoryVO.builder()
                .id(memory.getId())
                .content(memory.getContent())
                .sourceType(memory.getSourceType())
                .expiresAt(memory.getExpiresAt())
                .createdAt(memory.getCreatedAt())
                .updatedAt(memory.getUpdatedAt())
                .build();
    }
}
