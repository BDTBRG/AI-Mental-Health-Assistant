package com.amha.mapper;

import com.amha.entity.ConsultationContextSummary;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface ConsultationContextSummaryMapper extends BaseMapper<ConsultationContextSummary> {

    @Select("SELECT * FROM consultation_context_summary WHERE session_id = #{sessionId} "
            + "AND user_id = #{userId} LIMIT 1 FOR UPDATE")
    ConsultationContextSummary selectBySessionAndUserForUpdate(@Param("sessionId") Long sessionId,
                                                                 @Param("userId") Long userId);

    @Update("UPDATE consultation_context_summary SET summary_json = #{summaryJson}, "
            + "covered_through_message_id = #{coveredThroughMessageId}, "
            + "summary_version = #{expectedVersion} + 1, prompt_version = #{promptVersion}, "
            + "updated_at = #{updatedAt} WHERE session_id = #{sessionId} AND user_id = #{userId} "
            + "AND summary_version = #{expectedVersion}")
    int updateSummaryCas(@Param("sessionId") Long sessionId,
                         @Param("userId") Long userId,
                         @Param("expectedVersion") Long expectedVersion,
                         @Param("summaryJson") String summaryJson,
                         @Param("coveredThroughMessageId") Long coveredThroughMessageId,
                         @Param("promptVersion") String promptVersion,
                         @Param("updatedAt") LocalDateTime updatedAt);
}
