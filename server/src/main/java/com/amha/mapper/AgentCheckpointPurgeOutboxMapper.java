package com.amha.mapper;

import com.amha.entity.AgentCheckpointPurgeOutbox;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AgentCheckpointPurgeOutboxMapper extends BaseMapper<AgentCheckpointPurgeOutbox> {

    @Select("SELECT * FROM agent_checkpoint_purge_outbox WHERE session_id = #{sessionId} "
            + "LIMIT 1 FOR UPDATE")
    AgentCheckpointPurgeOutbox selectBySessionForUpdate(@Param("sessionId") Long sessionId);

    @Select("SELECT * FROM agent_checkpoint_purge_outbox WHERE "
            + "(status IN ('PENDING', 'DEAD') AND next_attempt_at <= CURRENT_TIMESTAMP) "
            + "OR (status = 'PROCESSING' AND lease_until <= CURRENT_TIMESTAMP) "
            + "ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED")
    AgentCheckpointPurgeOutbox selectNextReadyForUpdate();

    @Select("SELECT * FROM agent_checkpoint_purge_outbox WHERE id = #{id} FOR UPDATE")
    AgentCheckpointPurgeOutbox selectByIdForUpdate(@Param("id") Long id);
}
