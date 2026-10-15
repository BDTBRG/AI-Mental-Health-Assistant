package com.amha.mapper;

import com.amha.entity.ConsultationContextSummaryOutbox;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface ConsultationContextSummaryOutboxMapper
        extends BaseMapper<ConsultationContextSummaryOutbox> {

    @Select("SELECT * FROM consultation_context_summary_outbox WHERE session_id = #{sessionId} "
            + "AND user_id = #{userId} LIMIT 1")
    ConsultationContextSummaryOutbox selectBySessionAndUser(@Param("sessionId") Long sessionId,
                                                             @Param("userId") Long userId);

    @Select("SELECT * FROM consultation_context_summary_outbox WHERE session_id = #{sessionId} "
            + "LIMIT 1 FOR UPDATE")
    ConsultationContextSummaryOutbox selectBySessionForUpdate(@Param("sessionId") Long sessionId);

    @Select("SELECT * FROM consultation_context_summary_outbox WHERE id = #{id} FOR UPDATE")
    ConsultationContextSummaryOutbox selectByIdForUpdate(@Param("id") Long id);

    @Select("SELECT * FROM consultation_context_summary_outbox "
            + "WHERE (status = 'PENDING' AND next_attempt_at <= CURRENT_TIMESTAMP) "
            + "OR (status IN ('QUEUED', 'PROCESSING') AND lease_until <= CURRENT_TIMESTAMP) "
            + "ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED")
    ConsultationContextSummaryOutbox selectNextReadyForUpdate();

    @Select("SELECT COUNT(*) FROM consultation_context_summary_outbox WHERE status = #{status}")
    Long countByStatus(@Param("status") String status);

    @Select("SELECT MIN(created_at) FROM consultation_context_summary_outbox WHERE status = 'PENDING'")
    LocalDateTime selectOldestPendingAt();

    @Delete("DELETE FROM consultation_context_summary_outbox WHERE session_id = #{sessionId}")
    int deleteBySession(@Param("sessionId") Long sessionId);

    @Update("UPDATE consultation_context_summary_outbox SET status = 'PENDING', attempt_count = 0, "
            + "next_attempt_at = #{retriedAt}, last_error_code = NULL, lease_token = NULL, lease_until = NULL, "
            + "manual_retry_count = COALESCE(manual_retry_count, 0) + 1, last_manual_retry_by = #{actorUserId}, "
            + "last_manual_retry_at = #{retriedAt}, updated_at = #{retriedAt} "
            + "WHERE session_id = #{sessionId} AND user_id = #{userId} AND status = 'DEAD'")
    int retryDeadForOwner(@Param("sessionId") Long sessionId,
                          @Param("userId") Long userId,
                          @Param("actorUserId") Long actorUserId,
                          @Param("retriedAt") LocalDateTime retriedAt);
}
