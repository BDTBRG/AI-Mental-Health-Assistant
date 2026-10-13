package com.amha.mapper;

import com.amha.entity.EmotionAnalysisOutbox;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface EmotionAnalysisOutboxMapper extends BaseMapper<EmotionAnalysisOutbox> {

    @Select("SELECT * FROM emotion_analysis_outbox " +
            "WHERE (status = 'PENDING' AND next_attempt_at <= CURRENT_TIMESTAMP) " +
            "OR (status IN ('QUEUED', 'PROCESSING') AND lease_until <= CURRENT_TIMESTAMP) " +
            "ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED")
    EmotionAnalysisOutbox selectNextReadyForUpdate();

    @Select("SELECT * FROM emotion_analysis_outbox WHERE id = #{id} FOR UPDATE")
    EmotionAnalysisOutbox selectByIdForUpdate(Long id);
}
